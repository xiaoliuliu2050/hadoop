/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.fs.BatchedRemoteIterator.BatchedListEntries;
import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants.CRYPTO_XATTR_ENCRYPTION_ZONE;
import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants.CRYPTO_XATTR_FILE_ENCRYPTION_INFO;
import static org.apache.hadoop.util.Time.now;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CipherSuite;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotDirectoryException;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.XAttrHelper;
import org.apache.hadoop.hdfs.protocol.AclException;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockStoragePolicy;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.EncryptionZone;
import org.apache.hadoop.hdfs.protocol.FSLimitException.MaxDirectoryItemsExceededException;
import org.apache.hadoop.hdfs.protocol.FSLimitException.PathComponentTooLongException;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.protocol.SnapshotAccessControlException;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos;
import org.apache.hadoop.hdfs.protocolPB.PBHelper;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeStorageInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.INode.BlocksMapUpdateInfo;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.util.ByteArray;
import org.apache.hadoop.hdfs.util.ChunkedArrayList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Both FSDirectory and FSNamesystem manage the state of the namespace.
 * FSDirectory is a pure in-memory data structure, all of whose operations
 * happen entirely in memory. In contrast, FSNamesystem persists the operations
 * to the disk.
 * @see org.apache.hadoop.hdfs.server.namenode.FSNamesystem
 **/
@InterfaceAudience.Private
public class FSDirectory implements Closeable {
  static final Logger LOG = LoggerFactory.getLogger(FSDirectory.class);
  private static INodeDirectory createRoot(FSNamesystem namesystem) {
    final INodeDirectory r = new INodeDirectory(
        INodeId.ROOT_INODE_ID,
        INodeDirectory.ROOT_NAME,
        namesystem.createFsOwnerPermissions(new FsPermission((short) 0755)),
        0L);
    r.addDirectoryWithQuotaFeature(
        DirectoryWithQuotaFeature.DEFAULT_NAMESPACE_QUOTA,
        DirectoryWithQuotaFeature.DEFAULT_DISKSPACE_QUOTA);
    r.addSnapshottableFeature();
    r.setSnapshotQuota(0);
    return r;
  }

  @VisibleForTesting
  static boolean CHECK_RESERVED_FILE_NAMES = true;
  public final static String DOT_RESERVED_STRING = ".reserved";
  public final static String DOT_RESERVED_PATH_PREFIX = Path.SEPARATOR
      + DOT_RESERVED_STRING;
  public final static byte[] DOT_RESERVED = 
      DFSUtil.string2Bytes(DOT_RESERVED_STRING);
  private final static String RAW_STRING = "raw";
  private final static byte[] RAW = DFSUtil.string2Bytes(RAW_STRING);
  public final static String DOT_INODES_STRING = ".inodes";
  public final static byte[] DOT_INODES = 
      DFSUtil.string2Bytes(DOT_INODES_STRING);

  INodeDirectory rootDir;
  private final FSNamesystem namesystem;
  private volatile boolean skipQuotaCheck = false; //skip while consuming edits
  private final int maxComponentLength;
  private final int maxDirItems;
  private final int lsLimit;  // max list limit
  private final int contentCountLimit; // max content summary counts per run
  private final INodeMap inodeMap; // Synchronized by dirLock
  private long yieldCount = 0; // keep track of lock yield count.

  private final int inodeXAttrsLimit; //inode xattrs max limit

  // lock to protect the directory and BlockMap
  private final ReentrantReadWriteLock dirLock;

  private final boolean isPermissionEnabled;
  /**
   * Support for ACLs is controlled by a configuration flag. If the
   * configuration flag is false, then the NameNode will reject all
   * ACL-related operations.
   */
  private final boolean aclsEnabled;
  private final boolean xattrsEnabled;
  private final int xattrMaxSize;
  private final String fsOwnerShortUserName;
  private final String supergroup;
  private final INodeId inodeId;

  private final FSEditLog editLog;

  // utility methods to acquire and release read lock and write lock
  void readLock() {
    this.dirLock.readLock().lock();
  }

  void readUnlock() {
    this.dirLock.readLock().unlock();
  }

  void writeLock() {
    this.dirLock.writeLock().lock();
  }

  void writeUnlock() {
    this.dirLock.writeLock().unlock();
  }

  boolean hasWriteLock() {
    return this.dirLock.isWriteLockedByCurrentThread();
  }

  boolean hasReadLock() {
    return this.dirLock.getReadHoldCount() > 0 || hasWriteLock();
  }

  public int getReadHoldCount() {
    return this.dirLock.getReadHoldCount();
  }

  public int getWriteHoldCount() {
    return this.dirLock.getWriteHoldCount();
  }

  @VisibleForTesting
  public final EncryptionZoneManager ezManager;

  /**
   * Caches frequently used file names used in {@link INode} to reuse 
   * byte[] objects and reduce heap usage.
   */
  private final NameCache<ByteArray> nameCache;

  FSDirectory(FSNamesystem ns, Configuration conf) throws IOException {
    this.dirLock = new ReentrantReadWriteLock(true); // fair
    this.inodeId = new INodeId();
    rootDir = createRoot(ns);
    inodeMap = INodeMap.newInstance(rootDir);
    this.isPermissionEnabled = conf.getBoolean(
      DFSConfigKeys.DFS_PERMISSIONS_ENABLED_KEY,
      DFSConfigKeys.DFS_PERMISSIONS_ENABLED_DEFAULT);
    this.fsOwnerShortUserName =
      UserGroupInformation.getCurrentUser().getShortUserName();
    this.supergroup = conf.get(
      DFSConfigKeys.DFS_PERMISSIONS_SUPERUSERGROUP_KEY,
      DFSConfigKeys.DFS_PERMISSIONS_SUPERUSERGROUP_DEFAULT);
    this.aclsEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_KEY,
        DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_DEFAULT);
    LOG.info("ACLs enabled? " + aclsEnabled);
    this.xattrsEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_XATTRS_ENABLED_KEY,
        DFSConfigKeys.DFS_NAMENODE_XATTRS_ENABLED_DEFAULT);
    LOG.info("XAttrs enabled? " + xattrsEnabled);
    this.xattrMaxSize = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_XATTR_SIZE_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_XATTR_SIZE_DEFAULT);
    Preconditions.checkArgument(xattrMaxSize >= 0,
                                "Cannot set a negative value for the maximum size of an xattr (%s).",
                                DFSConfigKeys.DFS_NAMENODE_MAX_XATTR_SIZE_KEY);
    final String unlimited = xattrMaxSize == 0 ? " (unlimited)" : "";
    LOG.info("Maximum size of an xattr: " + xattrMaxSize + unlimited);
    int configuredLimit = conf.getInt(
        DFSConfigKeys.DFS_LIST_LIMIT, DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT);
    this.lsLimit = configuredLimit>0 ?
        configuredLimit : DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT;
    this.contentCountLimit = conf.getInt(
        DFSConfigKeys.DFS_CONTENT_SUMMARY_LIMIT_KEY,
        DFSConfigKeys.DFS_CONTENT_SUMMARY_LIMIT_DEFAULT);
    
    // filesystem limits
    this.maxComponentLength = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_COMPONENT_LENGTH_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_COMPONENT_LENGTH_DEFAULT);
    this.maxDirItems = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_DIRECTORY_ITEMS_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_DIRECTORY_ITEMS_DEFAULT);
    this.inodeXAttrsLimit = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_XATTRS_PER_INODE_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_XATTRS_PER_INODE_DEFAULT);

    Preconditions.checkArgument(this.inodeXAttrsLimit >= 0,
        "Cannot set a negative limit on the number of xattrs per inode (%s).",
        DFSConfigKeys.DFS_NAMENODE_MAX_XATTRS_PER_INODE_KEY);
    // We need a maximum maximum because by default, PB limits message sizes
    // to 64MB. This means we can only store approximately 6.7 million entries
    // per directory, but let's use 6.4 million for some safety.
    final int MAX_DIR_ITEMS = 64 * 100 * 1000;
    Preconditions.checkArgument(
        maxDirItems > 0 && maxDirItems <= MAX_DIR_ITEMS, "Cannot set "
            + DFSConfigKeys.DFS_NAMENODE_MAX_DIRECTORY_ITEMS_KEY
            + " to a value less than 1 or greater than " + MAX_DIR_ITEMS);

    int threshold = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_KEY,
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_DEFAULT);
    NameNode.LOG.info("Caching file names occuring more than " + threshold
        + " times");
    nameCache = new NameCache<ByteArray>(threshold);
    namesystem = ns;
    this.editLog = ns.getEditLog();
    ezManager = new EncryptionZoneManager(this, conf);
  }
    
  FSNamesystem getFSNamesystem() {
    return namesystem;
  }

  private BlockManager getBlockManager() {
    return getFSNamesystem().getBlockManager();
  }

  /** @return the root directory inode. */
  public INodeDirectory getRoot() {
    return rootDir;
  }

  boolean isPermissionEnabled() {
    return isPermissionEnabled;
  }
  boolean isAclsEnabled() {
    return aclsEnabled;
  }
  boolean isXattrsEnabled() {
    return xattrsEnabled;
  }
  int getXattrMaxSize() { return xattrMaxSize; }

  int getLsLimit() {
    return lsLimit;
  }

  int getContentCountLimit() {
    return contentCountLimit;
  }

  int getInodeXAttrsLimit() {
    return inodeXAttrsLimit;
  }

  FSEditLog getEditLog() {
    return editLog;
  }

  /**
   * Shutdown the filestore
   */
  @Override
  public void close() throws IOException {}

  void markNameCacheInitialized() {
    writeLock();
    try {
      nameCache.initialized();
    } finally {
      writeUnlock();
    }
  }

  boolean shouldSkipQuotaChecks() {
    return skipQuotaCheck;
  }

  /** Enable quota verification */
  void enableQuotaChecks() {
    skipQuotaCheck = false;
  }

  /** Disable quota verification */
  void disableQuotaChecks() {
    skipQuotaCheck = true;
  }

  private static INodeFile newINodeFile(long id, PermissionStatus permissions,
      long mtime, long atime, short replication, long preferredBlockSize) {
    return newINodeFile(id, permissions, mtime, atime, replication, preferredBlockSize,
        (byte)0);
  }

  private static INodeFile newINodeFile(long id, PermissionStatus permissions,
      long mtime, long atime, short replication, long preferredBlockSize,
      byte storagePolicyId) {
    return new INodeFile(id, null, permissions, mtime, atime,
        BlockInfo.EMPTY_ARRAY, replication, preferredBlockSize,
        storagePolicyId);
  }

  /**
   * Add the given filename to the fs.
   * @throws FileAlreadyExistsException
   * @throws QuotaExceededException
   * @throws UnresolvedLinkException
   * @throws SnapshotAccessControlException 
   */
  INodeFile addFile(String path, PermissionStatus permissions,
                    short replication, long preferredBlockSize,
                    String clientName, String clientMachine)
    throws FileAlreadyExistsException, QuotaExceededException,
      UnresolvedLinkException, SnapshotAccessControlException, AclException {

    long modTime = now();
    INodeFile newNode = newINodeFile(allocateNewInodeId(), permissions, modTime, modTime, replication, preferredBlockSize);
    newNode.toUnderConstruction(clientName, clientMachine);

    boolean added = false;
    writeLock();
    try {
      added = addINode(path, newNode);
    } finally {
      writeUnlock();
    }
    if (!added) {
      NameNode.stateChangeLog.info("DIR* addFile: failed to add " + path);
      return null;
    }

    if(NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* addFile: " + path + " is added");
    }
    return newNode;
  }

  INodeFile unprotectedAddFile( long id,
                            String path, 
                            PermissionStatus permissions,
                            List<AclEntry> aclEntries,
                            List<XAttr> xAttrs,
                            short replication,
                            long modificationTime,
                            long atime,
                            long preferredBlockSize,
                            boolean underConstruction,
                            String clientName,
                            String clientMachine,
                            byte storagePolicyId) {
    final INodeFile newNode;
    assert hasWriteLock();
    if (underConstruction) {
      newNode = newINodeFile(id, permissions, modificationTime,
          modificationTime, replication, preferredBlockSize, storagePolicyId);
      newNode.toUnderConstruction(clientName, clientMachine);

    } else {
      newNode = newINodeFile(id, permissions, modificationTime, atime,
          replication, preferredBlockSize, storagePolicyId);
    }

    try {
      if (addINode(path, newNode)) {
        if (aclEntries != null) {
          AclStorage.updateINodeAcl(newNode, aclEntries,
            Snapshot.CURRENT_STATE_ID);
        }
        if (xAttrs != null) {
          XAttrStorage.updateINodeXAttrs(newNode, xAttrs,
              Snapshot.CURRENT_STATE_ID);
        }
        return newNode;
      }
    } catch (IOException e) {
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug(
            "DIR* FSDirectory.unprotectedAddFile: exception when add " + path
                + " to the file system", e);
      }
    }
    return null;
  }

  /**
   * Add a block to the file. Returns a reference to the added block.
   */
  BlockInfo addBlock(String path, INodesInPath inodesInPath, Block block,
      DatanodeStorageInfo[] targets) throws IOException {
    writeLock();
    try {
      final INodeFile fileINode = inodesInPath.getLastINode().asFile();
      Preconditions.checkState(fileINode.isUnderConstruction());

      // check quota limits and updated space consumed
      updateCount(inodesInPath, 0, fileINode.getBlockDiskspace(), true);

      // associate new last block for the file
      BlockInfoUnderConstruction blockInfo =
        new BlockInfoUnderConstruction(
            block,
            fileINode.getFileReplication(),
            BlockUCState.UNDER_CONSTRUCTION,
            targets);
      getBlockManager().addBlockCollection(blockInfo, fileINode);
      fileINode.addBlock(blockInfo);

      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.addBlock: "
            + path + " with " + block
            + " block is added to the in-memory "
            + "file system");
      }
      return blockInfo;
    } finally {
      writeUnlock();
    }
  }

  /**
   * Remove a block from the file.
   * @return Whether the block exists in the corresponding file
   */
  boolean removeBlock(String path, INodeFile fileNode, Block block)
      throws IOException {
    Preconditions.checkArgument(fileNode.isUnderConstruction());
    writeLock();
    try {
      return unprotectedRemoveBlock(path, fileNode, block);
    } finally {
      writeUnlock();
    }
  }
  
  boolean unprotectedRemoveBlock(String path,
      INodeFile fileNode, Block block) throws IOException {
    // modify file-> block and blocksMap
    // fileNode should be under construction
    boolean removed = fileNode.removeLastBlock(block);
    if (!removed) {
      return false;
    }
    getBlockManager().removeBlockFromMap(block);

    if(NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.removeBlock: "
          +path+" with "+block
          +" block is removed from the file system");
    }

    // update space consumed
    final INodesInPath iip = getINodesInPath4Write(path, true);
    updateCount(iip, 0, -fileNode.getBlockDiskspace(), true);
    return true;
  }

  /**
   * This is a wrapper for resolvePath(). If the path passed
   * is prefixed with /.reserved/raw, then it checks to ensure that the caller
   * has super user privileges.
   *
   * @param pc The permission checker used when resolving path.
   * @param path The path to resolve.
   * @param pathComponents path components corresponding to the path
   * @return if the path indicates an inode, return path after replacing up to
   *         <inodeid> with the corresponding path of the inode, else the path
   *         in {@code src} as is. If the path refers to a path in the "raw"
   *         directory, return the non-raw pathname.
   * @throws FileNotFoundException
   * @throws AccessControlException
   */
  String resolvePath(FSPermissionChecker pc, String path, byte[][] pathComponents)
      throws FileNotFoundException, AccessControlException {
    if (isReservedRawName(path) && isPermissionEnabled) {
      pc.checkSuperuserPrivilege();
    }
    return resolvePath(path, pathComponents, this);
  }

  /**
   * Set file replication
   * 
   * @param src file name
   * @param replication new replication
   * @param blockRepls block replications - output parameter
   * @return array of file blocks
   * @throws QuotaExceededException
   * @throws SnapshotAccessControlException 
   */
  Block[] setReplication(String src, short replication, short[] blockRepls)
      throws QuotaExceededException, UnresolvedLinkException,
      SnapshotAccessControlException {
    writeLock();
    try {
      return unprotectedSetReplication(src, replication, blockRepls);
    } finally {
      writeUnlock();
    }
  }

  Block[] unprotectedSetReplication(String src, short replication,
      short[] blockRepls) throws QuotaExceededException,
      UnresolvedLinkException, SnapshotAccessControlException {
    assert hasWriteLock();

    final INodesInPath iip = getINodesInPath4Write(src, true);
    final INode inode = iip.getLastINode();
    if (inode == null || !inode.isFile()) {
      return null;
    }
    INodeFile file = inode.asFile();
    final short oldBR = file.getBlockReplication();

    // before setFileReplication, check for increasing block replication.
    // if replication > oldBR, then newBR == replication.
    // if replication < oldBR, we don't know newBR yet. 
    if (replication > oldBR) {
      long dsDelta = (replication - oldBR)*(file.diskspaceConsumed()/oldBR);
      updateCount(iip, 0, dsDelta, true);
    }

    file.setFileReplication(replication, iip.getLatestSnapshotId());
    
    final short newBR = file.getBlockReplication(); 
    // check newBR < oldBR case. 
    if (newBR < oldBR) {
      long dsDelta = (newBR - oldBR)*(file.diskspaceConsumed()/newBR);
      updateCount(iip, 0, dsDelta, true);
    }

    if (blockRepls != null) {
      blockRepls[0] = oldBR;
      blockRepls[1] = newBR;
    }
    return file.getBlocks();
  }

  /** Set block storage policy for a directory */
  void setStoragePolicy(INodesInPath iip, byte policyId)
      throws IOException {
    writeLock();
    try {
      unprotectedSetStoragePolicy(iip, policyId);
    } finally {
      writeUnlock();
    }
  }

  void unprotectedSetStoragePolicy(INodesInPath iip, byte policyId)
      throws IOException {
    assert hasWriteLock();
    final INode inode = iip.getLastINode();
    if (inode == null) {
      throw new FileNotFoundException("File/Directory does not exist: "
          + iip.getPath());
    }
    final int snapshotId = iip.getLatestSnapshotId();
    if (inode.isFile()) {
      BlockStoragePolicy newPolicy = getBlockManager().getStoragePolicy(policyId);
      if (newPolicy.isCopyOnCreateFile()) {
        throw new HadoopIllegalArgumentException(
            "Policy " + newPolicy + " cannot be set after file creation.");
      }

      BlockStoragePolicy currentPolicy =
          getBlockManager().getStoragePolicy(inode.getLocalStoragePolicyID());

      if (currentPolicy != null && currentPolicy.isCopyOnCreateFile()) {
        throw new HadoopIllegalArgumentException(
            "Existing policy " + currentPolicy.getName() +
                " cannot be changed after file creation.");
      }
      inode.asFile().setStoragePolicyID(policyId, snapshotId);
    } else if (inode.isDirectory()) {
      setDirStoragePolicy(inode.asDirectory(), policyId, snapshotId);  
    } else {
      throw new FileNotFoundException(iip.getPath()
          + " is not a file or directory");
    }
  }

  private void setDirStoragePolicy(INodeDirectory inode, byte policyId,
      int latestSnapshotId) throws IOException {
    List<XAttr> existingXAttrs = XAttrStorage.readINodeXAttrs(inode);
    XAttr xAttr = BlockStoragePolicySuite.buildXAttr(policyId);
    List<XAttr> newXAttrs = FSDirXAttrOp.setINodeXAttrs(this, existingXAttrs,
                                                        Arrays.asList(xAttr),
                                                        EnumSet.of(
                                                            XAttrSetFlag.CREATE,
                                                            XAttrSetFlag.REPLACE));
    XAttrStorage.updateINodeXAttrs(inode, newXAttrs, latestSnapshotId);
  }

  /**
   * @param path the file path
   * @return the block size of the file. 
   */
  long getPreferredBlockSize(String path) throws IOException {
    readLock();
    try {
      return INodeFile.valueOf(getNode(path, false), path
          ).getPreferredBlockSize();
    } finally {
      readUnlock();
    }
  }

  void setPermission(String src, FsPermission permission)
      throws FileNotFoundException, UnresolvedLinkException,
      QuotaExceededException, SnapshotAccessControlException {
    writeLock();
    try {
      unprotectedSetPermission(src, permission);
    } finally {
      writeUnlock();
    }
  }
  
  void unprotectedSetPermission(String src, FsPermission permissions)
      throws FileNotFoundException, UnresolvedLinkException,
      QuotaExceededException, SnapshotAccessControlException {
    assert hasWriteLock();
    final INodesInPath inodesInPath = getINodesInPath4Write(src, true);
    final INode inode = inodesInPath.getLastINode();
    if (inode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    int snapshotId = inodesInPath.getLatestSnapshotId();
    inode.setPermission(permissions, snapshotId);
  }

  void setOwner(String src, String username, String groupname)
      throws FileNotFoundException, UnresolvedLinkException,
      QuotaExceededException, SnapshotAccessControlException {
    writeLock();
    try {
      unprotectedSetOwner(src, username, groupname);
    } finally {
      writeUnlock();
    }
  }

  void unprotectedSetOwner(String src, String username, String groupname)
      throws FileNotFoundException, UnresolvedLinkException,
      QuotaExceededException, SnapshotAccessControlException {
    assert hasWriteLock();
    final INodesInPath inodesInPath = getINodesInPath4Write(src, true);
    INode inode = inodesInPath.getLastINode();
    if (inode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    if (username != null) {
      inode = inode.setUser(username, inodesInPath.getLatestSnapshotId());
    }
    if (groupname != null) {
      inode.setGroup(groupname, inodesInPath.getLatestSnapshotId());
    }
  }

  /**
   * Delete the target directory and collect the blocks under it
   * 
   * @param src Path of a directory to delete
   * @param collectedBlocks Blocks under the deleted directory
   * @param removedINodes INodes that should be removed from {@link #inodeMap}
   * @return the number of files that have been removed
   */
  long delete(String src, BlocksMapUpdateInfo collectedBlocks,
              List<INode> removedINodes, long mtime) throws IOException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.delete: " + src);
    }
    final long filesRemoved;
    writeLock();
    try {
      final INodesInPath inodesInPath = getINodesInPath4Write(
          normalizePath(src), false);
      if (!deleteAllowed(inodesInPath, src) ) {
        filesRemoved = -1;
      } else {
        List<INodeDirectory> snapshottableDirs = new ArrayList<INodeDirectory>();
        FSDirSnapshotOp.checkSnapshot(inodesInPath.getLastINode(), snapshottableDirs);
        filesRemoved = unprotectedDelete(inodesInPath, collectedBlocks,
            removedINodes, mtime);
        namesystem.removeSnapshottableDirs(snapshottableDirs);
      }
    } finally {
      writeUnlock();
    }
    return filesRemoved;
  }
  
  private static boolean deleteAllowed(final INodesInPath iip,
      final String src) {
    if (iip.length() < 1 || iip.getLastINode() == null) {
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
            + "failed to remove " + src + " because it does not exist");
      }
      return false;
    } else if (iip.length() == 1) { // src is the root
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedDelete: "
          + "failed to remove " + src
          + " because the root is not allowed to be deleted");
      return false;
    }
    return true;
  }
  
  /**
   * @return true if the path is a non-empty directory; otherwise, return false.
   */
  boolean isNonEmptyDirectory(INodesInPath inodesInPath) {
    readLock();
    try {
      final INode inode = inodesInPath.getLastINode();
      if (inode == null || !inode.isDirectory()) {
        //not found or not a directory
        return false;
      }
      final int s = inodesInPath.getPathSnapshotId();
      return !inode.asDirectory().getChildrenList(s).isEmpty();
    } finally {
      readUnlock();
    }
  }

  /**
   * Delete a path from the name space
   * Update the count at each ancestor directory with quota
   * <br>
   * Note: This is to be used by {@link FSEditLog} only.
   * <br>
   * @param src a string representation of a path to an inode
   * @param mtime the time the inode is removed
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  void unprotectedDelete(String src, long mtime) throws UnresolvedLinkException,
      QuotaExceededException, SnapshotAccessControlException, IOException {
    assert hasWriteLock();
    BlocksMapUpdateInfo collectedBlocks = new BlocksMapUpdateInfo();
    List<INode> removedINodes = new ChunkedArrayList<INode>();

    final INodesInPath inodesInPath = getINodesInPath4Write(
        normalizePath(src), false);
    long filesRemoved = -1;
    if (deleteAllowed(inodesInPath, src)) {
      List<INodeDirectory> snapshottableDirs = new ArrayList<INodeDirectory>();
      FSDirSnapshotOp.checkSnapshot(inodesInPath.getLastINode(), snapshottableDirs);
      filesRemoved = unprotectedDelete(inodesInPath, collectedBlocks,
          removedINodes, mtime);
      namesystem.removeSnapshottableDirs(snapshottableDirs); 
    }

    if (filesRemoved >= 0) {
      getFSNamesystem().removePathAndBlocks(src, collectedBlocks, 
          removedINodes, false);
    }
  }
  
  /**
   * Delete a path from the name space
   * Update the count at each ancestor directory with quota
   * @param iip the inodes resolved from the path
   * @param collectedBlocks blocks collected from the deleted path
   * @param removedINodes inodes that should be removed from {@link #inodeMap}
   * @param mtime the time the inode is removed
   * @return the number of inodes deleted; 0 if no inodes are deleted.
   */ 
  long unprotectedDelete(INodesInPath iip, BlocksMapUpdateInfo collectedBlocks,
      List<INode> removedINodes, long mtime) throws QuotaExceededException {
    assert hasWriteLock();

    // check if target node exists
    INode targetNode = iip.getLastINode();
    if (targetNode == null) {
      return -1;
    }

    // record modification
    final int latestSnapshot = iip.getLatestSnapshotId();
    targetNode.recordModification(latestSnapshot);

    // Remove the node from the namespace
    long removed = removeLastINode(iip);
    if (removed == -1) {
      return -1;
    }

    // set the parent's modification time
    final INodeDirectory parent = targetNode.getParent();
    parent.updateModificationTime(mtime, latestSnapshot);
    if (removed == 0) {
      return 0;
    }
    
    // collect block
    if (!targetNode.isInLatestSnapshot(latestSnapshot)) {
      targetNode.destroyAndCollectBlocks(collectedBlocks, removedINodes);
    } else {
      Quota.Counts counts = targetNode.cleanSubtree(Snapshot.CURRENT_STATE_ID,
          latestSnapshot, collectedBlocks, removedINodes, true);
      parent.addSpaceConsumed(-counts.get(Quota.NAMESPACE),
          -counts.get(Quota.DISKSPACE), true);
      removed = counts.get(Quota.NAMESPACE);
    }
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
          + iip.getPath() + " is removed");
    }
    return removed;
  }

  byte getStoragePolicyID(byte inodePolicy, byte parentPolicy) {
    return inodePolicy != BlockStoragePolicySuite.ID_UNSPECIFIED ? inodePolicy :
        parentPolicy;
  }

  INode getINode4DotSnapshot(String src) throws UnresolvedLinkException {
    Preconditions.checkArgument(
        src.endsWith(HdfsConstants.SEPARATOR_DOT_SNAPSHOT_DIR),
        "%s does not end with %s", src, HdfsConstants.SEPARATOR_DOT_SNAPSHOT_DIR);
    
    final String dirPath = normalizePath(src.substring(0,
        src.length() - HdfsConstants.DOT_SNAPSHOT_DIR.length()));
    
    final INode node = this.getINode(dirPath);
    if (node != null && node.isDirectory()
        && node.asDirectory().isSnapshottable()) {
      return node;
    }
    return null;
  }

  INodesInPath getExistingPathINodes(byte[][] components)
      throws UnresolvedLinkException {
    return INodesInPath.resolve(rootDir, components);
  }

  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INode getINode(String src) throws UnresolvedLinkException {
    return getLastINodeInPath(src).getINode(0);
  }

  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INodesInPath getLastINodeInPath(String src)
       throws UnresolvedLinkException {
    readLock();
    try {
      return getLastINodeInPath(src, true);
    } finally {
      readUnlock();
    }
  }

  /**
   * Get {@link INode} associated with the file / directory.
   */
  public INodesInPath getINodesInPath4Write(String src
      ) throws UnresolvedLinkException, SnapshotAccessControlException {
    readLock();
    try {
      return getINodesInPath4Write(src, true);
    } finally {
      readUnlock();
    }
  }

  /**
   * Get {@link INode} associated with the file / directory.
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  public INode getINode4Write(String src) throws UnresolvedLinkException,
      SnapshotAccessControlException {
    readLock();
    try {
      return getINode4Write(src, true);
    } finally {
      readUnlock();
    }
  }

  /** 
   * Check whether the filepath could be created
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  boolean isValidToCreate(String src) throws UnresolvedLinkException,
      SnapshotAccessControlException {
    String srcs = normalizePath(src);
    readLock();
    try {
      return srcs.startsWith("/") && !srcs.endsWith("/")
              && getINode4Write(srcs, false) == null;
    } finally {
      readUnlock();
    }
  }

  /**
   * Check whether the path specifies a directory
   */
  boolean isDir(String src) throws UnresolvedLinkException {
    src = normalizePath(src);
    readLock();
    try {
      INode node = getNode(src, false);
      return node != null && node.isDirectory();
    } finally {
      readUnlock();
    }
  }

  /** Updates namespace and diskspace consumed for all
   * directories until the parent directory of file represented by path.
   * 
   * @param path path for the file.
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @throws QuotaExceededException if the new count violates any quota limit
   * @throws FileNotFoundException if path does not exist.
   */
  void updateSpaceConsumed(String path, long nsDelta, long dsDelta)
      throws QuotaExceededException, FileNotFoundException,
          UnresolvedLinkException, SnapshotAccessControlException {
    writeLock();
    try {
      final INodesInPath iip = getINodesInPath4Write(path, false);
      if (iip.getLastINode() == null) {
        throw new FileNotFoundException("Path not found: " + path);
      }
      updateCount(iip, nsDelta, dsDelta, true);
    } finally {
      writeUnlock();
    }
  }
  
  private void updateCount(INodesInPath iip, long nsDelta, long dsDelta,
      boolean checkQuota) throws QuotaExceededException {
    updateCount(iip, iip.length() - 1, nsDelta, dsDelta, checkQuota);
  }

  /** update count of each inode with quota
   * 
   * @param iip inodes in a path
   * @param numOfINodes the number of inodes to update starting from index 0
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @param checkQuota if true then check if quota is exceeded
   * @throws QuotaExceededException if the new count violates any quota limit
   */
  private void updateCount(INodesInPath iip, int numOfINodes, 
                           long nsDelta, long dsDelta, boolean checkQuota)
                           throws QuotaExceededException {
    assert hasWriteLock();
    if (!namesystem.isImageLoaded()) {
      //still initializing. do not check or update quotas.
      return;
    }
    if (numOfINodes > iip.length()) {
      numOfINodes = iip.length();
    }
    if (checkQuota && !skipQuotaCheck) {
      verifyQuota(iip, numOfINodes, nsDelta, dsDelta, null);
    }
    unprotectedUpdateCount(iip, numOfINodes, nsDelta, dsDelta);
  }
  
  /** 
   * update quota of each inode and check to see if quota is exceeded. 
   * See {@link #updateCount(INodesInPath, long, long, boolean)}
   */ 
  private void updateCountNoQuotaCheck(INodesInPath inodesInPath,
      int numOfINodes, long nsDelta, long dsDelta) {
    assert hasWriteLock();
    try {
      updateCount(inodesInPath, numOfINodes, nsDelta, dsDelta, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.error("BUG: unexpected exception ", e);
    }
  }
  
  /**
   * updates quota without verification
   * callers responsibility is to make sure quota is not exceeded
   */
  static void unprotectedUpdateCount(INodesInPath inodesInPath,
      int numOfINodes, long nsDelta, long dsDelta) {
    for(int i=0; i < numOfINodes; i++) {
      if (inodesInPath.getINode(i).isQuotaSet()) { // a directory with quota
        inodesInPath.getINode(i).asDirectory().getDirectoryWithQuotaFeature()
            .addSpaceConsumed2Cache(nsDelta, dsDelta);
      }
    }
  }
  
  /** Return the name of the path represented by inodes at [0, pos] */
  static String getFullPathName(INode[] inodes, int pos) {
    StringBuilder fullPathName = new StringBuilder();
    if (inodes[0].isRoot()) {
      if (pos == 0) return Path.SEPARATOR;
    } else {
      fullPathName.append(inodes[0].getLocalName());
    }
    
    for (int i=1; i<=pos; i++) {
      fullPathName.append(Path.SEPARATOR_CHAR).append(inodes[i].getLocalName());
    }
    return fullPathName.toString();
  }

  /**
   * @return the relative path of an inode from one of its ancestors,
   *         represented by an array of inodes.
   */
  private static INode[] getRelativePathINodes(INode inode, INode ancestor) {
    // calculate the depth of this inode from the ancestor
    int depth = 0;
    for (INode i = inode; i != null && !i.equals(ancestor); i = i.getParent()) {
      depth++;
    }
    INode[] inodes = new INode[depth];

    // fill up the inodes in the path from this inode to root
    for (int i = 0; i < depth; i++) {
      if (inode == null) {
        NameNode.stateChangeLog.warn("Could not get full path."
            + " Corresponding file might have deleted already.");
        return null;
      }
      inodes[depth-i-1] = inode;
      inode = inode.getParent();
    }
    return inodes;
  }
  
  private static INode[] getFullPathINodes(INode inode) {
    return getRelativePathINodes(inode, null);
  }
  
  /** Return the full path name of the specified inode */
  static String getFullPathName(INode inode) {
    INode[] inodes = getFullPathINodes(inode);
    // inodes can be null only when its called without holding lock
    return inodes == null ? "" : getFullPathName(inodes, inodes.length - 1);
  }

  /**
   * Add the given child to the namespace.
   * @param src The full path name of the child node.
   * @throws QuotaExceededException is thrown if it violates quota limit
   */
  private boolean addINode(String src, INode child)
      throws QuotaExceededException, UnresolvedLinkException {
    byte[][] components = INode.getPathComponents(src);
    child.setLocalName(components[components.length-1]);
    cacheName(child);
    writeLock();
    try {
      final INodesInPath iip = getExistingPathINodes(components);
      return addLastINode(iip, child, true);
    } finally {
      writeUnlock();
    }
  }

  /**
   * Verify quota for adding or moving a new INode with required 
   * namespace and diskspace to a given position.
   *  
   * @param iip INodes corresponding to a path
   * @param pos position where a new INode will be added
   * @param nsDelta needed namespace
   * @param dsDelta needed diskspace
   * @param commonAncestor Last node in inodes array that is a common ancestor
   *          for a INode that is being moved from one location to the other.
   *          Pass null if a node is not being moved.
   * @throws QuotaExceededException if quota limit is exceeded.
   */
  static void verifyQuota(INodesInPath iip, int pos, long nsDelta,
      long dsDelta, INode commonAncestor) throws QuotaExceededException {
    if (nsDelta <= 0 && dsDelta <= 0) {
      // if quota is being freed or not being consumed
      return;
    }

    // check existing components in the path
    for(int i = (pos > iip.length() ? iip.length(): pos) - 1; i >= 0; i--) {
      if (commonAncestor == iip.getINode(i)) {
        // Stop checking for quota when common ancestor is reached
        return;
      }
      final DirectoryWithQuotaFeature q
          = iip.getINode(i).asDirectory().getDirectoryWithQuotaFeature();
      if (q != null) { // a directory with quota
        try {
          q.verifyQuota(nsDelta, dsDelta);
        } catch (QuotaExceededException e) {
          List<INode> inodes = iip.getReadOnlyINodes();
          final String path = getFullPathName(inodes.toArray(new INode[inodes.size()]), i);
          e.setPathName(path);
          throw e;
        }
      }
    }
  }

  /** Verify if the inode name is legal. */
  void verifyINodeName(byte[] childName) throws HadoopIllegalArgumentException {
    if (Arrays.equals(HdfsConstants.DOT_SNAPSHOT_DIR_BYTES, childName)) {
      String s = "\"" + HdfsConstants.DOT_SNAPSHOT_DIR + "\" is a reserved name.";
      if (!namesystem.isImageLoaded()) {
        s += "  Please rename it before upgrade.";
      }
      throw new HadoopIllegalArgumentException(s);
    }
  }

  /**
   * Verify child's name for fs limit.
   *
   * @param childName byte[] containing new child name
   * @param parentPath String containing parent path
   * @throws PathComponentTooLongException child's name is too long.
   */
  void verifyMaxComponentLength(byte[] childName, String parentPath)
      throws PathComponentTooLongException {
    if (maxComponentLength == 0) {
      return;
    }

    final int length = childName.length;
    if (length > maxComponentLength) {
      final PathComponentTooLongException e = new PathComponentTooLongException(
          maxComponentLength, length, parentPath,
          DFSUtil.bytes2String(childName));
      if (namesystem.isImageLoaded()) {
        throw e;
      } else {
        // Do not throw if edits log is still being processed
        NameNode.LOG.error("ERROR in FSDirectory.verifyINodeName", e);
      }
    }
  }

  /**
   * Verify children size for fs limit.
   *
   * @throws MaxDirectoryItemsExceededException too many children.
   */
  void verifyMaxDirItems(INodeDirectory parent, String parentPath)
      throws MaxDirectoryItemsExceededException {
    final int count = parent.getChildrenList(Snapshot.CURRENT_STATE_ID).size();
    if (count >= maxDirItems) {
      final MaxDirectoryItemsExceededException e
          = new MaxDirectoryItemsExceededException(maxDirItems, count);
      if (namesystem.isImageLoaded()) {
        e.setPathName(parentPath);
        throw e;
      } else {
        // Do not throw if edits log is still being processed
        NameNode.LOG.error("FSDirectory.verifyMaxDirItems: "
            + e.getLocalizedMessage());
      }
    }
  }
  
  /**
   * The same as {@link #addChild(INodesInPath, int, INode, boolean)}
   * with pos = length - 1.
   */
  private boolean addLastINode(INodesInPath inodesInPath, INode inode,
      boolean checkQuota) throws QuotaExceededException {
    final int pos = inodesInPath.length() - 1;
    return addChild(inodesInPath, pos, inode, checkQuota);
  }

  /** Add a node child to the inodes at index pos. 
   * Its ancestors are stored at [0, pos-1].
   * @return false if the child with this name already exists; 
   *         otherwise return true;
   * @throws QuotaExceededException is thrown if it violates quota limit
   */
  boolean addChild(INodesInPath iip, int pos, INode child, boolean checkQuota)
      throws QuotaExceededException {
    // Disallow creation of /.reserved. This may be created when loading
    // editlog/fsimage during upgrade since /.reserved was a valid name in older
    // release. This may also be called when a user tries to create a file
    // or directory /.reserved.
    if (pos == 1 && iip.getINode(0) == rootDir && isReservedName(child)) {
      throw new HadoopIllegalArgumentException(
          "File name \"" + child.getLocalName() + "\" is reserved and cannot "
              + "be created. If this is during upgrade change the name of the "
              + "existing file or directory to another name before upgrading "
              + "to the new release.");
    }
    final INodeDirectory parent = iip.getINode(pos-1).asDirectory();
    // The filesystem limits are not really quotas, so this check may appear
    // odd. It's because a rename operation deletes the src, tries to add
    // to the dest, if that fails, re-adds the src from whence it came.
    // The rename code disables the quota when it's restoring to the
    // original location becase a quota violation would cause the the item
    // to go "poof".  The fs limits must be bypassed for the same reason.
    if (checkQuota) {
      final String parentPath = iip.getPath(pos - 1);
      verifyMaxComponentLength(child.getLocalNameBytes(), parentPath);
      verifyMaxDirItems(parent, parentPath);
    }
    // always verify inode name
    verifyINodeName(child.getLocalNameBytes());
    
    final Quota.Counts counts = child.computeQuotaUsage();
    updateCount(iip, pos,
        counts.get(Quota.NAMESPACE), counts.get(Quota.DISKSPACE), checkQuota);
    boolean isRename = (child.getParent() != null);
    boolean added;
    try {
      added = parent.addChild(child, true, iip.getLatestSnapshotId());
    } catch (QuotaExceededException e) {
      updateCountNoQuotaCheck(iip, pos,
          -counts.get(Quota.NAMESPACE), -counts.get(Quota.DISKSPACE));
      throw e;
    }
    if (!added) {
      updateCountNoQuotaCheck(iip, pos,
          -counts.get(Quota.NAMESPACE), -counts.get(Quota.DISKSPACE));
    } else {
      if (!isRename) {
        AclStorage.copyINodeDefaultAcl(child);
      }
      addToInodeMap(child);
    }
    return added;
  }
  
  boolean addLastINodeNoQuotaCheck(INodesInPath inodesInPath, INode i) {
    try {
      return addLastINode(inodesInPath, i, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.warn("FSDirectory.addChildNoQuotaCheck - unexpected", e);
    }
    return false;
  }
  
  /**
   * Remove the last inode in the path from the namespace.
   * Count of each ancestor with quota is also updated.
   * @return -1 for failing to remove;
   *          0 for removing a reference whose referred inode has other 
   *            reference nodes;
   *         >0 otherwise. 
   */
  long removeLastINode(final INodesInPath iip)
      throws QuotaExceededException {
    final int latestSnapshot = iip.getLatestSnapshotId();
    final INode last = iip.getLastINode();
    final INodeDirectory parent = iip.getINode(-2).asDirectory();
    if (!parent.removeChild(last, latestSnapshot)) {
      return -1;
    }
    
    if (!last.isInLatestSnapshot(latestSnapshot)) {
      final Quota.Counts counts = last.computeQuotaUsage();
      updateCountNoQuotaCheck(iip, iip.length() - 1,
          -counts.get(Quota.NAMESPACE), -counts.get(Quota.DISKSPACE));

      if (INodeReference.tryRemoveReference(last) > 0) {
        return 0;
      } else {
        return counts.get(Quota.NAMESPACE);
      }
    }
    return 1;
  }

  static String normalizePath(String src) {
    if (src.length() > 1 && src.endsWith("/")) {
      src = src.substring(0, src.length() - 1);
    }
    return src;
  }

  @VisibleForTesting
  public long getYieldCount() {
    return yieldCount;
  }

  void addYieldCount(long value) {
    yieldCount += value;
  }

  public INodeMap getINodeMap() {
    return inodeMap;
  }
  
  /**
   * This method is always called with writeLock of FSDirectory held.
   */
  public final void addToInodeMap(INode inode) {
    if (inode instanceof INodeWithAdditionalFields) {
      inodeMap.put(inode);
      if (!inode.isSymlink()) {
        final XAttrFeature xaf = inode.getXAttrFeature();
        if (xaf != null) {
          final List<XAttr> xattrs = xaf.getXAttrs();
          for (XAttr xattr : xattrs) {
            final String xaName = XAttrHelper.getPrefixName(xattr);
            if (CRYPTO_XATTR_ENCRYPTION_ZONE.equals(xaName)) {
              try {
                final HdfsProtos.ZoneEncryptionInfoProto ezProto =
                    HdfsProtos.ZoneEncryptionInfoProto.parseFrom(
                        xattr.getValue());
                ezManager.unprotectedAddEncryptionZone(inode.getId(),
                    PBHelper.convert(ezProto.getSuite()),
                    PBHelper.convert(ezProto.getCryptoProtocolVersion()),
                    ezProto.getKeyName());
              } catch (InvalidProtocolBufferException e) {
                NameNode.LOG.warn("Error parsing protocol buffer of " +
                    "EZ XAttr " + xattr.getName());
              }
            }
          }
        }
      }
    }
  }
  
  /**
   * This method is always called with writeLock of FSDirectory held.
   */
  public final void removeFromInodeMap(List<? extends INode> inodes) {
    if (inodes != null) {
      for (INode inode : inodes) {
        if (inode != null && inode instanceof INodeWithAdditionalFields) {
          inodeMap.remove(inode);
          ezManager.removeEncryptionZone(inode.getId());
        }
      }
    }
  }
  
  /**
   * Get the inode from inodeMap based on its inode id.
   * @param id The given id
   * @return The inode associated with the given id
   */
  public INode getInode(long id) {
    readLock();
    try {
      return inodeMap.get(id);
    } finally {
      readUnlock();
    }
  }
  
  @VisibleForTesting
  int getInodeMapSize() {
    return inodeMap.size();
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the contract.
   * Sets quota for for a directory.
   * @return INodeDirectory if any of the quotas have changed. null otherwise.
   * @throws FileNotFoundException if the path does not exist.
   * @throws PathIsNotDirectoryException if the path is not a directory.
   * @throws QuotaExceededException if the directory tree size is 
   *                                greater than the given quota
   * @throws UnresolvedLinkException if a symlink is encountered in src.
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  INodeDirectory unprotectedSetQuota(String src, long nsQuota, long dsQuota)
      throws FileNotFoundException, PathIsNotDirectoryException,
      QuotaExceededException, UnresolvedLinkException,
      SnapshotAccessControlException {
    assert hasWriteLock();
    // sanity check
    if ((nsQuota < 0 && nsQuota != HdfsConstants.QUOTA_DONT_SET && 
         nsQuota != HdfsConstants.QUOTA_RESET) || 
        (dsQuota < 0 && dsQuota != HdfsConstants.QUOTA_DONT_SET && 
          dsQuota != HdfsConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Illegal value for nsQuota or " +
                                         "dsQuota : " + nsQuota + " and " +
                                         dsQuota);
    }
    
    String srcs = normalizePath(src);
    final INodesInPath iip = getINodesInPath4Write(srcs, true);
    INodeDirectory dirNode = INodeDirectory.valueOf(iip.getLastINode(), srcs);
    if (dirNode.isRoot() && nsQuota == HdfsConstants.QUOTA_RESET) {
      throw new IllegalArgumentException("Cannot clear namespace quota on root.");
    } else { // a directory inode
      final Quota.Counts oldQuota = dirNode.getQuotaCounts();
      final long oldNsQuota = oldQuota.get(Quota.NAMESPACE);
      final long oldDsQuota = oldQuota.get(Quota.DISKSPACE);
      if (nsQuota == HdfsConstants.QUOTA_DONT_SET) {
        nsQuota = oldNsQuota;
      }
      if (dsQuota == HdfsConstants.QUOTA_DONT_SET) {
        dsQuota = oldDsQuota;
      }        
      if (oldNsQuota == nsQuota && oldDsQuota == dsQuota) {
        return null;
      }

      final int latest = iip.getLatestSnapshotId();
      dirNode.recordModification(latest);
      dirNode.setQuota(nsQuota, dsQuota);
      return dirNode;
    }
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the contract.
   * @return INodeDirectory if any of the quotas have changed. null otherwise.
   * @throws SnapshotAccessControlException if path is in RO snapshot
   * @see #unprotectedSetQuota(String, long, long)
   */
  INodeDirectory setQuota(String src, long nsQuota, long dsQuota)
      throws FileNotFoundException, PathIsNotDirectoryException,
      QuotaExceededException, UnresolvedLinkException,
      SnapshotAccessControlException {
    writeLock();
    try {
      return unprotectedSetQuota(src, nsQuota, dsQuota);
    } finally {
      writeUnlock();
    }
  }
  
  long totalInodes() {
    readLock();
    try {
      return rootDir.getDirectoryWithQuotaFeature().getSpaceConsumed()
          .get(Quota.NAMESPACE);
    } finally {
      readUnlock();
    }
  }

  /**
   * Sets the access time on the file/directory. Logs it in the transaction log.
   */
  boolean setTimes(INode inode, long mtime, long atime, boolean force,
                   int latestSnapshotId) throws QuotaExceededException {
    writeLock();
    try {
      return unprotectedSetTimes(inode, mtime, atime, force, latestSnapshotId);
    } finally {
      writeUnlock();
    }
  }

  boolean unprotectedSetTimes(String src, long mtime, long atime, boolean force) 
      throws UnresolvedLinkException, QuotaExceededException {
    assert hasWriteLock();
    final INodesInPath i = getLastINodeInPath(src); 
    return unprotectedSetTimes(i.getLastINode(), mtime, atime, force,
        i.getLatestSnapshotId());
  }

  private boolean unprotectedSetTimes(INode inode, long mtime,
      long atime, boolean force, int latest) throws QuotaExceededException {
    assert hasWriteLock();
    boolean status = false;
    if (mtime != -1) {
      inode = inode.setModificationTime(mtime, latest);
      status = true;
    }
    if (atime != -1) {
      long inodeTime = inode.getAccessTime();

      // if the last access time update was within the last precision interval, then
      // no need to store access time
      if (atime <= inodeTime + getFSNamesystem().getAccessTimePrecision() && !force) {
        status =  false;
      } else {
        inode.setAccessTime(atime, latest);
        status = true;
      }
    } 
    return status;
  }

  /**
   * Reset the entire namespace tree.
   */
  void reset() {
    writeLock();
    try {
      rootDir = createRoot(getFSNamesystem());
      inodeMap.clear();
      addToInodeMap(rootDir);
      nameCache.reset();
      inodeId.setCurrentValue(INodeId.LAST_RESERVED_ID);
    } finally {
      writeUnlock();
    }
  }

  /**
   * Add the specified path into the namespace.
   */
  INodeSymlink addSymlink(long id, String path, String target,
                          long mtime, long atime, PermissionStatus perm)
          throws UnresolvedLinkException, QuotaExceededException {
    writeLock();
    try {
      return unprotectedAddSymlink(id, path, target, mtime, atime, perm);
    } finally {
      writeUnlock();
    }
  }

  INodeSymlink unprotectedAddSymlink(long id, String path, String target,
      long mtime, long atime, PermissionStatus perm)
      throws UnresolvedLinkException, QuotaExceededException {
    assert hasWriteLock();
    final INodeSymlink symlink = new INodeSymlink(id, null, perm, mtime, atime,
        target);
    return addINode(path, symlink) ? symlink : null;
  }

  boolean isInAnEZ(INodesInPath iip)
      throws UnresolvedLinkException, SnapshotAccessControlException {
    readLock();
    try {
      return ezManager.isInAnEZ(iip);
    } finally {
      readUnlock();
    }
  }

  String getKeyName(INodesInPath iip) {
    readLock();
    try {
      return ezManager.getKeyName(iip);
    } finally {
      readUnlock();
    }
  }

  XAttr createEncryptionZone(String src, CipherSuite suite,
      CryptoProtocolVersion version, String keyName)
    throws IOException {
    writeLock();
    try {
      return ezManager.createEncryptionZone(src, suite, version, keyName);
    } finally {
      writeUnlock();
    }
  }

  EncryptionZone getEZForPath(INodesInPath iip) {
    readLock();
    try {
      return ezManager.getEZINodeForPath(iip);
    } finally {
      readUnlock();
    }
  }

  BatchedListEntries<EncryptionZone> listEncryptionZones(long prevId)
      throws IOException {
    readLock();
    try {
      return ezManager.listEncryptionZones(prevId);
    } finally {
      readUnlock();
    }
  }

  /**
   * Set the FileEncryptionInfo for an INode.
   */
  void setFileEncryptionInfo(String src, FileEncryptionInfo info)
      throws IOException {
    // Make the PB for the xattr
    final HdfsProtos.PerFileEncryptionInfoProto proto =
        PBHelper.convertPerFileEncInfo(info);
    final byte[] protoBytes = proto.toByteArray();
    final XAttr fileEncryptionAttr =
        XAttrHelper.buildXAttr(CRYPTO_XATTR_FILE_ENCRYPTION_INFO, protoBytes);
    final List<XAttr> xAttrs = Lists.newArrayListWithCapacity(1);
    xAttrs.add(fileEncryptionAttr);

    writeLock();
    try {
      FSDirXAttrOp.unprotectedSetXAttrs(this, src, xAttrs,
                                        EnumSet.of(XAttrSetFlag.CREATE));
    } finally {
      writeUnlock();
    }
  }

  /**
   * This function combines the per-file encryption info (obtained
   * from the inode's XAttrs), and the encryption info from its zone, and
   * returns a consolidated FileEncryptionInfo instance. Null is returned
   * for non-encrypted files.
   *
   * @param inode inode of the file
   * @param snapshotId ID of the snapshot that
   *                   we want to get encryption info from
   * @param iip inodes in the path containing the file, passed in to
   *            avoid obtaining the list of inodes again; if iip is
   *            null then the list of inodes will be obtained again
   * @return consolidated file encryption info; null for non-encrypted files
   */
  FileEncryptionInfo getFileEncryptionInfo(INode inode, int snapshotId,
      INodesInPath iip) throws IOException {
    if (!inode.isFile()) {
      return null;
    }
    readLock();
    try {
      EncryptionZone encryptionZone = getEZForPath(iip);
      if (encryptionZone == null) {
        // not an encrypted file
        return null;
      } else if(encryptionZone.getPath() == null
          || encryptionZone.getPath().isEmpty()) {
        if (NameNode.LOG.isDebugEnabled()) {
          NameNode.LOG.debug("Encryption zone " +
              encryptionZone.getPath() + " does not have a valid path.");
        }
      }

      final CryptoProtocolVersion version = encryptionZone.getVersion();
      final CipherSuite suite = encryptionZone.getSuite();
      final String keyName = encryptionZone.getKeyName();

      XAttr fileXAttr = FSDirXAttrOp.unprotectedGetXAttrByName(inode,
                                                               snapshotId,
                                                               CRYPTO_XATTR_FILE_ENCRYPTION_INFO);

      if (fileXAttr == null) {
        NameNode.LOG.warn("Could not find encryption XAttr for file " +
            iip.getPath() + " in encryption zone " + encryptionZone.getPath());
        return null;
      }

      try {
        HdfsProtos.PerFileEncryptionInfoProto fileProto =
            HdfsProtos.PerFileEncryptionInfoProto.parseFrom(
                fileXAttr.getValue());
        return PBHelper.convert(fileProto, suite, version, keyName);
      } catch (InvalidProtocolBufferException e) {
        throw new IOException("Could not parse file encryption info for " +
            "inode " + inode, e);
      }
    } finally {
      readUnlock();
    }
  }

  static INode resolveLastINode(String src, INodesInPath iip)
      throws FileNotFoundException {
    INode inode = iip.getLastINode();
    if (inode == null) {
      throw new FileNotFoundException("cannot find " + src);
    }
    return inode;
  }

  /**
   * Caches frequently used file names to reuse file name objects and
   * reduce heap size.
   */
  void cacheName(INode inode) {
    // Name is cached only for files
    if (!inode.isFile()) {
      return;
    }
    ByteArray name = new ByteArray(inode.getLocalNameBytes());
    name = nameCache.put(name);
    if (name != null) {
      inode.setLocalName(name.getBytes());
    }
  }
  
  void shutdown() {
    nameCache.reset();
    inodeMap.clear();
  }
  
  /**
   * Given an INode get all the path complents leading to it from the root.
   * If an Inode corresponding to C is given in /A/B/C, the returned
   * patch components will be {root, A, B, C}.
   * Note that this method cannot handle scenarios where the inode is in a
   * snapshot.
   */
  public static byte[][] getPathComponents(INode inode) {
    List<byte[]> components = new ArrayList<byte[]>();
    components.add(0, inode.getLocalNameBytes());
    while(inode.getParent() != null) {
      components.add(0, inode.getParent().getLocalNameBytes());
      inode = inode.getParent();
    }
    return components.toArray(new byte[components.size()][]);
  }

  /**
   * @return path components for reserved path, else null.
   */
  static byte[][] getPathComponentsForReservedPath(String src) {
    return !isReservedName(src) ? null : INode.getPathComponents(src);
  }

  /** Check if a given inode name is reserved */
  public static boolean isReservedName(INode inode) {
    return CHECK_RESERVED_FILE_NAMES
            && Arrays.equals(inode.getLocalNameBytes(), DOT_RESERVED);
  }

  /** Check if a given path is reserved */
  public static boolean isReservedName(String src) {
    return src.startsWith(DOT_RESERVED_PATH_PREFIX);
  }

  static boolean isReservedRawName(String src) {
    return src.startsWith(DOT_RESERVED_PATH_PREFIX +
        Path.SEPARATOR + RAW_STRING);
  }

  /**
   * Resolve a /.reserved/... path to a non-reserved path.
   * <p/>
   * There are two special hierarchies under /.reserved/:
   * <p/>
   * /.reserved/.inodes/<inodeid> performs a path lookup by inodeid,
   * <p/>
   * /.reserved/raw/... returns the encrypted (raw) bytes of a file in an
   * encryption zone. For instance, if /ezone is an encryption zone, then
   * /ezone/a refers to the decrypted file and /.reserved/raw/ezone/a refers to
   * the encrypted (raw) bytes of /ezone/a.
   * <p/>
   * Pathnames in the /.reserved/raw directory that resolve to files not in an
   * encryption zone are equivalent to the corresponding non-raw path. Hence,
   * if /a/b/c refers to a file that is not in an encryption zone, then
   * /.reserved/raw/a/b/c is equivalent (they both refer to the same
   * unencrypted file).
   * 
   * @param src path that is being processed
   * @param pathComponents path components corresponding to the path
   * @param fsd FSDirectory
   * @return if the path indicates an inode, return path after replacing up to
   *         <inodeid> with the corresponding path of the inode, else the path
   *         in {@code src} as is. If the path refers to a path in the "raw"
   *         directory, return the non-raw pathname.
   * @throws FileNotFoundException if inodeid is invalid
   */
  static String resolvePath(String src, byte[][] pathComponents,
      FSDirectory fsd) throws FileNotFoundException {
    final int nComponents = (pathComponents == null) ?
        0 : pathComponents.length;
    if (nComponents <= 2) {
      return src;
    }
    if (!Arrays.equals(DOT_RESERVED, pathComponents[1])) {
      /* This is not a /.reserved/ path so do nothing. */
      return src;
    }

    if (Arrays.equals(DOT_INODES, pathComponents[2])) {
      /* It's a /.reserved/.inodes path. */
      if (nComponents > 3) {
        return resolveDotInodesPath(src, pathComponents, fsd);
      } else {
        return src;
      }
    } else if (Arrays.equals(RAW, pathComponents[2])) {
      /* It's /.reserved/raw so strip off the /.reserved/raw prefix. */
      if (nComponents == 3) {
        return Path.SEPARATOR;
      } else {
        return constructRemainingPath("", pathComponents, 3);
      }
    } else {
      /* It's some sort of /.reserved/<unknown> path. Ignore it. */
      return src;
    }
  }

  private static String resolveDotInodesPath(String src,
      byte[][] pathComponents, FSDirectory fsd)
      throws FileNotFoundException {
    final String inodeId = DFSUtil.bytes2String(pathComponents[3]);
    final long id;
    try {
      id = Long.parseLong(inodeId);
    } catch (NumberFormatException e) {
      throw new FileNotFoundException("Invalid inode path: " + src);
    }
    if (id == INodeId.ROOT_INODE_ID && pathComponents.length == 4) {
      return Path.SEPARATOR;
    }
    INode inode = fsd.getInode(id);
    if (inode == null) {
      throw new FileNotFoundException(
          "File for given inode path does not exist: " + src);
    }
    
    // Handle single ".." for NFS lookup support.
    if ((pathComponents.length > 4)
        && DFSUtil.bytes2String(pathComponents[4]).equals("..")) {
      INode parent = inode.getParent();
      if (parent == null || parent.getId() == INodeId.ROOT_INODE_ID) {
        // inode is root, or its parent is root.
        return Path.SEPARATOR;
      } else {
        return parent.getFullPathName();
      }
    }

    String path = "";
    if (id != INodeId.ROOT_INODE_ID) {
      path = inode.getFullPathName();
    }
    return constructRemainingPath(path, pathComponents, 4);
  }

  private static String constructRemainingPath(String pathPrefix,
      byte[][] pathComponents, int startAt) {

    StringBuilder path = new StringBuilder(pathPrefix);
    for (int i = startAt; i < pathComponents.length; i++) {
      path.append(Path.SEPARATOR).append(
          DFSUtil.bytes2String(pathComponents[i]));
    }
    if (NameNode.LOG.isDebugEnabled()) {
      NameNode.LOG.debug("Resolved path is " + path);
    }
    return path.toString();
  }

  /** @return the {@link INodesInPath} containing only the last inode. */
  INodesInPath getLastINodeInPath(
      String path, boolean resolveLink) throws UnresolvedLinkException {
    return INodesInPath.resolve(rootDir, INode.getPathComponents(path), 1,
            resolveLink);
  }

  /** @return the {@link INodesInPath} containing all inodes in the path. */
  INodesInPath getINodesInPath(String path, boolean resolveLink
  ) throws UnresolvedLinkException {
    final byte[][] components = INode.getPathComponents(path);
    return INodesInPath.resolve(rootDir, components, components.length,
            resolveLink);
  }

  /** @return the last inode in the path. */
  INode getNode(String path, boolean resolveLink)
          throws UnresolvedLinkException {
    return getLastINodeInPath(path, resolveLink).getINode(0);
  }

  /**
   * @return the INode of the last component in src, or null if the last
   * component does not exist.
   * @throws UnresolvedLinkException if symlink can't be resolved
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  INode getINode4Write(String src, boolean resolveLink)
          throws UnresolvedLinkException, SnapshotAccessControlException {
    return getINodesInPath4Write(src, resolveLink).getLastINode();
  }

  /**
   * @return the INodesInPath of the components in src
   * @throws UnresolvedLinkException if symlink can't be resolved
   * @throws SnapshotAccessControlException if path is in RO snapshot
   */
  INodesInPath getINodesInPath4Write(String src, boolean resolveLink)
          throws UnresolvedLinkException, SnapshotAccessControlException {
    final byte[][] components = INode.getPathComponents(src);
    INodesInPath inodesInPath = INodesInPath.resolve(rootDir, components,
            components.length, resolveLink);
    if (inodesInPath.isSnapshot()) {
      throw new SnapshotAccessControlException(
              "Modification on a read-only snapshot is disallowed");
    }
    return inodesInPath;
  }

  FSPermissionChecker getPermissionChecker()
    throws AccessControlException {
    try {
      return new FSPermissionChecker(fsOwnerShortUserName, supergroup,
          NameNode.getRemoteUser());
    } catch (IOException ioe) {
      throw new AccessControlException(ioe);
    }
  }

  void checkOwner(FSPermissionChecker pc, INodesInPath iip)
      throws AccessControlException {
    checkPermission(pc, iip, true, null, null, null, null);
  }

  void checkPathAccess(FSPermissionChecker pc, INodesInPath iip,
      FsAction access) throws AccessControlException {
    checkPermission(pc, iip, false, null, null, access, null);
  }
  void checkParentAccess(FSPermissionChecker pc, INodesInPath iip,
      FsAction access) throws AccessControlException {
    checkPermission(pc, iip, false, null, access, null, null);
  }

  void checkAncestorAccess(FSPermissionChecker pc, INodesInPath iip,
      FsAction access) throws AccessControlException {
    checkPermission(pc, iip, false, access, null, null, null);
  }

  void checkTraverse(FSPermissionChecker pc, INodesInPath iip)
      throws AccessControlException {
    checkPermission(pc, iip, false, null, null, null, null);
  }

  /**
   * Check whether current user have permissions to access the path. For more
   * details of the parameters, see
   * {@link FSPermissionChecker#checkPermission}.
   */
  void checkPermission(FSPermissionChecker pc, INodesInPath iip,
      boolean doCheckOwner, FsAction ancestorAccess, FsAction parentAccess,
      FsAction access, FsAction subAccess)
    throws AccessControlException {
    checkPermission(pc, iip, doCheckOwner, ancestorAccess,
        parentAccess, access, subAccess, false);
  }

  /**
   * Check whether current user have permissions to access the path. For more
   * details of the parameters, see
   * {@link FSPermissionChecker#checkPermission}.
   */
  void checkPermission(FSPermissionChecker pc, INodesInPath iip,
      boolean doCheckOwner, FsAction ancestorAccess, FsAction parentAccess,
      FsAction access, FsAction subAccess, boolean ignoreEmptyDir)
      throws AccessControlException {
    if (!pc.isSuperUser()) {
      readLock();
      try {
        pc.checkPermission(iip, doCheckOwner, ancestorAccess,
            parentAccess, access, subAccess, ignoreEmptyDir);
      } finally {
        readUnlock();
      }
    }
  }

  HdfsFileStatus getAuditFileInfo(String path, boolean resolveSymlink)
    throws IOException {
    return (namesystem.isAuditEnabled() && namesystem.isExternalInvocation())
      ? FSDirStatAndListingOp.getFileInfo(this, path, resolveSymlink, false,
        false) : null;
  }

  /**
   * Verify that parent directory of src exists.
   */
  void verifyParentDir(INodesInPath iip, String src)
      throws FileNotFoundException, ParentNotDirectoryException {
    Path parent = new Path(src).getParent();
    if (parent != null) {
      final INode parentNode = iip.getINode(-2);
      if (parentNode == null) {
        throw new FileNotFoundException("Parent directory doesn't exist: "
            + parent);
      } else if (!parentNode.isDirectory() && !parentNode.isSymlink()) {
        throw new ParentNotDirectoryException("Parent path is not a directory: "
            + parent);
      }
    }
  }

  /** Allocate a new inode ID. */
  long allocateNewInodeId() {
    return inodeId.nextValue();
  }

  /** @return the last inode ID. */
  public long getLastInodeId() {
    return inodeId.getCurrentValue();
  }

  /**
   * Set the last allocated inode id when fsimage or editlog is loaded.
   */
  void resetLastInodeId(long newValue) throws IOException {
    try {
      inodeId.skipTo(newValue);
    } catch(IllegalStateException ise) {
      throw new IOException(ise);
    }
  }

  /** Should only be used for tests to reset to any value */
  void resetLastInodeIdWithoutChecking(long newValue) {
    inodeId.setCurrentValue(newValue);
  }
}
