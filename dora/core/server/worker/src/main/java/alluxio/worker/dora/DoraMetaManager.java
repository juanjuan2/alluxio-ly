/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.dora;

import alluxio.AlluxioURI;
import alluxio.client.file.cache.CacheManager;
import alluxio.client.file.cache.PageId;
import alluxio.file.FileId;
import alluxio.grpc.FileInfo;
import alluxio.proto.meta.DoraMeta;
import alluxio.proto.meta.DoraMeta.FileStatus;
import alluxio.underfs.Fingerprint;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;

import com.google.common.base.Strings;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

/**
 * The Dora metadata manager that orchestrates the metadata operations.
 *
 * TODO(elega) Invalidating page cache synchronously causes performance issue and currently it
 *  also lacks concurrency control. Address this problem in the future.
 */
public class DoraMetaManager {
  private final DoraMetaStore mMetastore;
  private final CacheManager mCacheManager;
  private final PagedDoraWorker mDoraWorker;
  private final UnderFileSystem mUfs;

  /**
   * Creates a dora meta manager.
   * @param doraWorker the dora worker instance
   * @param metaStore the dora meta store
   * @param cacheManger the cache manager to manage the page cache
   * @param ufs the associated ufs
   */
  public DoraMetaManager(
      PagedDoraWorker doraWorker, DoraMetaStore metaStore, CacheManager cacheManger,
      UnderFileSystem ufs) {
    mMetastore = metaStore;
    mCacheManager = cacheManger;
    mDoraWorker = doraWorker;
    mUfs = ufs;
  }

  /**
   * Gets file meta from UFS.
   * @param path the full ufs path
   * @return the file status, or empty optional if not found
   */
  public Optional<FileStatus> getFromUfs(String path) throws IOException {
    try {
      UfsStatus status = mUfs.getStatus(path);
      DoraMeta.FileStatus fs = mDoraWorker.buildFileStatusFromUfsStatus(status, path);
      return Optional.ofNullable(fs);
    } catch (FileNotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Gets file meta from UFS and loads it into metastore if exists.
   * If the file does not exist in the UFS, clean up metadata and data.
   *
   * @param path the full ufs path
   * @return the file status, or empty optional if not found
   */
  public Optional<FileStatus> loadFromUfs(String path) throws IOException {
    Optional<FileStatus> fileStatus = getFromUfs(path);
    if (fileStatus.isEmpty()) {
      removeFromMetaStore(path);
    } else {
      put(path, fileStatus.get());
    }
    return fileStatus;
  }

  /**
   * Gets file meta from the metastore.
   * @param path the full ufs path
   * @return the file status, or empty optional if not found
   */
  public Optional<FileStatus> getFromMetaStore(String path) {
    return mMetastore.getDoraMeta(path);
  }

  /**
   * Puts meta of a file into the metastore, and invalidates the file data cache.
   * @param path the full ufs path
   * @param status the file meta
   */
  public void put(String path, FileStatus status) {
    Optional<FileStatus> existingStatus = mMetastore.getDoraMeta(path);
    if (existingStatus.isEmpty()
        || existingStatus.get().getFileInfo().getFolder()
        || existingStatus.get().getFileInfo().getLength() == 0) {
      mMetastore.putDoraMeta(path, status);
      return;
    }
    if (shouldInvalidatePageCache(existingStatus.get().getFileInfo(), status.getFileInfo())) {
      invalidateCachedFile(path, existingStatus.get().getFileInfo().getLength());
    }
    mMetastore.putDoraMeta(path, status);
  }

  /**
   * Removes meta of a file from the meta store.
   * @param path the full ufs path
   * @return the removed file meta, if exists
   */
  public Optional<FileStatus> removeFromMetaStore(String path) {
    Optional<FileStatus> status = mMetastore.getDoraMeta(path);
    if (status.isPresent()) {
      mMetastore.removeDoraMeta(path);
      invalidateCachedFile(path, status.get().getFileInfo().getLength());
    }
    return status;
  }

  private void invalidateCachedFile(String path, long length) {
    FileId fileId = FileId.of(AlluxioURI.hash(path));
    mCacheManager.deleteFile(fileId.toString());
    for (PageId page: mCacheManager.getCachedPageIdsByFileId(fileId.toString(), length)) {
      mCacheManager.delete(page);
    }
  }

  /**
   * Decides if the page cache should be invalidated if the file metadata is updated.
   * Similar to {@link alluxio.underfs.Fingerprint#matchContent(Fingerprint)},
   * if the update metadata matches any of the following, we consider the page cache
   * should be invalidated:
   * 1. the file type changed (from file to directory or directory to file)
   * 2. the ufs type changed (e.g. from s3 to hdfs)
   * 3. the file content does not match or is null
   * @param origin the origin file info from metastore
   * @param updated the updated file into to add to the metastore
   * @return true if the page cache (if any) should be invalidated, otherwise false
   */
  private boolean shouldInvalidatePageCache(FileInfo origin, FileInfo updated) {
    if (!mUfs.getUnderFSType().equals(origin.getUfsType())) {
      return true;
    }
    if (origin.getFolder() != updated.getFolder()) {
      return true;
    }
    // Keep the page cache in the most conservative way.
    // If content hash not set, page cache will be cleared.
    return Strings.isNullOrEmpty(origin.getContentHash())
        || Strings.isNullOrEmpty(updated.getContentHash())
        || !origin.getContentHash().equals(updated.getContentHash());
  }
}
