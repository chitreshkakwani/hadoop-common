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
package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.server.nodemanager.DeletionService;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceEvent;


/**
 * A collection of {@link LocalizedResource}s all of same
 * {@link LocalResourceVisibility}.
 * 
 */

class LocalResourcesTrackerImpl implements LocalResourcesTracker {

  static final Log LOG = LogFactory.getLog(LocalResourcesTrackerImpl.class);
  private static final String RANDOM_DIR_REGEX = "-?\\d+";
  private static final Pattern RANDOM_DIR_PATTERN = Pattern
      .compile(RANDOM_DIR_REGEX);

  private final String user;
  private final Dispatcher dispatcher;
  private final ConcurrentMap<LocalResourceRequest,LocalizedResource> localrsrc;
  private Configuration conf;
  /*
   * This flag controls whether this resource tracker uses hierarchical
   * directories or not. For PRIVATE and PUBLIC resource trackers it
   * will be set whereas for APPLICATION resource tracker it would
   * be false.
   */
  private final boolean useLocalCacheDirectoryManager;
  private ConcurrentHashMap<Path, LocalCacheDirectoryManager> directoryManagers;
  /*
   * It is used to keep track of resource into hierarchical directory
   * while it is getting downloaded. It is useful for reference counting
   * in case resource localization fails.
   */
  private ConcurrentHashMap<LocalResourceRequest, Path>
    inProgressLocalResourcesMap;

  public LocalResourcesTrackerImpl(String user, Dispatcher dispatcher,
      boolean useLocalCacheDirectoryManager, Configuration conf) {
    this(user, dispatcher,
      new ConcurrentHashMap<LocalResourceRequest, LocalizedResource>(),
      useLocalCacheDirectoryManager, conf);
  }

  LocalResourcesTrackerImpl(String user, Dispatcher dispatcher,
      ConcurrentMap<LocalResourceRequest,LocalizedResource> localrsrc,
      boolean useLocalCacheDirectoryManager, Configuration conf) {
    this.user = user;
    this.dispatcher = dispatcher;
    this.localrsrc = localrsrc;
    this.useLocalCacheDirectoryManager = useLocalCacheDirectoryManager;
    if ( this.useLocalCacheDirectoryManager) {
      directoryManagers = new ConcurrentHashMap<Path, LocalCacheDirectoryManager>();
      inProgressLocalResourcesMap =
        new ConcurrentHashMap<LocalResourceRequest, Path>();
    }
    this.conf = conf;
  }

  @Override
  public void handle(ResourceEvent event) {
    LocalResourceRequest req = event.getLocalResourceRequest();
    LocalizedResource rsrc = localrsrc.get(req);
    switch (event.getType()) {
    case REQUEST:
    case LOCALIZED:
      if (rsrc != null && (!isResourcePresent(rsrc))) {
        LOG.info("Resource " + rsrc.getLocalPath()
            + " is missing, localizing it again");
        localrsrc.remove(req);
        decrementFileCountForLocalCacheDirectory(req, rsrc);
        rsrc = null;
      }
      if (null == rsrc) {
        rsrc = new LocalizedResource(req, dispatcher);
        localrsrc.put(req, rsrc);
      }
      break;
    case RELEASE:
      if (null == rsrc) {
        LOG.info("Release unknown rsrc null (discard)");
        return;
      }
      break;
    }
    rsrc.handle(event);
  }

  /*
   * Update the file-count statistics for a local cache-directory.
   * This will retrieve the localized path for the resource from
   * 1) inProgressRsrcMap if the resource was under localization and it
   * failed.
   * 2) LocalizedResource if the resource is already localized.
   * From this path it will identify the local directory under which the
   * resource was localized. Then rest of the path will be used to decrement
   * file count for the HierarchicalSubDirectory pointing to this relative
   * path.
   */
  private void decrementFileCountForLocalCacheDirectory(LocalResourceRequest req,
      LocalizedResource rsrc) {
    if ( useLocalCacheDirectoryManager) {
      Path rsrcPath = null;
      if (inProgressLocalResourcesMap.containsKey(req)) {
        // This happens when localization of a resource fails.
        rsrcPath = inProgressLocalResourcesMap.remove(req);
      } else if (rsrc != null && rsrc.getLocalPath() != null) {
        rsrcPath = rsrc.getLocalPath().getParent().getParent();
      }
      if (rsrcPath != null) {
        Path parentPath = new Path(rsrcPath.toUri().getRawPath());
        while (!directoryManagers.containsKey(parentPath)) {
          parentPath = parentPath.getParent();
          if ( parentPath == null) {
            return;
          }
        }
        if ( parentPath != null) {
          String parentDir = parentPath.toUri().getRawPath().toString();
          LocalCacheDirectoryManager dir = directoryManagers.get(parentPath);
          String rsrcDir = rsrcPath.toUri().getRawPath(); 
          if (rsrcDir.equals(parentDir)) {
            dir.decrementFileCountForPath("");
          } else {
            dir.decrementFileCountForPath(
              rsrcDir.substring(
              parentDir.length() + 1));
          }
        }
      }
    }
  }

/**
   * This module checks if the resource which was localized is already present
   * or not
   * 
   * @param rsrc
   * @return true/false based on resource is present or not
   */
  public boolean isResourcePresent(LocalizedResource rsrc) {
    boolean ret = true;
    if (rsrc.getState() == ResourceState.LOCALIZED) {
      File file = new File(rsrc.getLocalPath().toUri().getRawPath().
        toString());
      if (!file.exists()) {
        ret = false;
      }
    }
    return ret;
  }
  
  @Override
  public boolean contains(LocalResourceRequest resource) {
    return localrsrc.containsKey(resource);
  }

  @Override
  public boolean remove(LocalizedResource rem, DeletionService delService) {
 // current synchronization guaranteed by crude RLS event for cleanup
    LocalizedResource rsrc = localrsrc.get(rem.getRequest());
    if (null == rsrc) {
      LOG.error("Attempt to remove absent resource: " + rem.getRequest()
          + " from " + getUser());
      return true;
    }
    if (rsrc.getRefCount() > 0
        || ResourceState.DOWNLOADING.equals(rsrc.getState()) || rsrc != rem) {
      // internal error
      LOG.error("Attempt to remove resource: " + rsrc
          + " with non-zero refcount");
      return false;
    } else { // ResourceState is LOCALIZED or INIT
      localrsrc.remove(rem.getRequest());
      if (ResourceState.LOCALIZED.equals(rsrc.getState())) {
        delService.delete(getUser(), getPathToDelete(rsrc.getLocalPath()));
      }
      decrementFileCountForLocalCacheDirectory(rem.getRequest(), rsrc);
      return true;
    }
  }

  /**
   * Returns the path up to the random directory component.
   */
  private Path getPathToDelete(Path localPath) {
    Path delPath = localPath.getParent();
    String name = delPath.getName();
    Matcher matcher = RANDOM_DIR_PATTERN.matcher(name);
    if (matcher.matches()) {
      return delPath;
    } else {
      LOG.warn("Random directory component did not match. " +
      		"Deleting localized path only");
      return localPath;
    }
  }

  @Override
  public String getUser() {
    return user;
  }

  @Override
  public Iterator<LocalizedResource> iterator() {
    return localrsrc.values().iterator();
  }

  /**
   * @return {@link Path} absolute path for localization which includes local
   *         directory path and the relative hierarchical path (if use local
   *         cache directory manager is enabled)
   * 
   * @param {@link LocalResourceRequest} Resource localization request to
   *        localize the resource.
   * @param {@link Path} local directory path
   */
  @Override
  public Path
      getPathForLocalization(LocalResourceRequest req, Path localDirPath) {
    if (useLocalCacheDirectoryManager && localDirPath != null) {

      if (!directoryManagers.containsKey(localDirPath)) {
        directoryManagers.putIfAbsent(localDirPath,
          new LocalCacheDirectoryManager(conf));
      }
      LocalCacheDirectoryManager dir = directoryManagers.get(localDirPath);

      Path rPath = localDirPath;
      String hierarchicalPath = dir.getRelativePathForLocalization();
      // For most of the scenarios we will get root path only which
      // is an empty string
      if (!hierarchicalPath.isEmpty()) {
        rPath = new Path(localDirPath, hierarchicalPath);
      }
      inProgressLocalResourcesMap.put(req, rPath);
      return rPath;
    } else {
      return localDirPath;
    }
  }

  @Override
  public void localizationCompleted(LocalResourceRequest req,
      boolean success) {
    if (useLocalCacheDirectoryManager) {
      if (!success) {
        decrementFileCountForLocalCacheDirectory(req, null);
      } else {
        inProgressLocalResourcesMap.remove(req);
      }
    }
  }
}