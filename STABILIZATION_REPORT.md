# MarkIt Hub - Stabilization & Optimization Report

## Overview
This report details the changes made to stabilize the MarkIt Hub application, specifically targeting the `SyncEngine` to resolve synchronization failures and improve performance.

## Key Changes

### 1. Fix for `FileNotFoundException` (Stability)
**Issue:** The app was crashing or failing to sync when it attempted to access a file using a stale URI (e.g., if a file was moved or deleted by another app like Syncthing, but MarkIt Hub still had the old URI in its database).
**Resolution:**
*   Updated `mergeProviderChangesIntoFile`, `saveTaskFile`, and `saveToFile`.
*   Added a validation check (`isFileAccessible`) before trusting any cached `sourceUri`.
*   If a URI is found to be invalid, the engine now gracefully falls back to searching for the file by its relative path.
*   Added robust error handling around file operations to prevent crashes from stopping the entire sync process.

### 2. Directory Listing Cache (Performance)
**Issue:** The sync process was performing redundant `DocumentsContract` queries. For every file operation (find, create, rename), the engine was re-scanning directory contents. This is an expensive operation, especially with SAF (Storage Access Framework).
**Resolution:**
*   Implemented `childrenCache` in `SyncEngine`.
*   `listChildDocuments` now caches the results of directory listings for the duration of a sync session.
*   This significantly speeds up:
    *   Path resolution (`findDocumentInPath`).
    *   Folder scanning (`scanFolderOptimized`).
    *   Collision detection (checking if `File (1).md` exists).

### 3. Cache Invalidation (Correctness)
**Issue:** With caching introduced, there was a risk of the engine acting on outdated data if it modified files (e.g., created a new task) and then tried to find it immediately.
**Resolution:**
*   Added `invalidateCache(uri)` calls to all modification methods:
    *   `saveTaskFile` (create/rename)
    *   `completeTask` (move/delete)
    *   `saveToFile` (create/move/rename)
    *   `deleteFile` (move/delete)
    *   `getOrCreateFolder` (create)
*   This ensures that after the app changes the file system, the next read operation fetches the fresh state.

## Technical Details
*   **File:** `app/src/main/java/com/waph1/markithub/util/SyncEngine.kt`
*   **New Method:** `isFileAccessible(uri: Uri): Boolean` - Lightweight check using `ContentResolver`.
*   **New Property:** `childrenCache: MutableMap<Uri, List<DocumentInfo>>`

## Recommendations for Testing
1.  **Sync robustness:** Try moving a file using a file manager while the app is closed, then run a sync. It should now handle this gracefully.
2.  **Performance:** Observe the sync speed on folders with many files. It should be noticeably faster.
3.  **Task Life-cycle:** Create, complete, and delete tasks to verify the cache invalidation logic works (files should correctly move to `.Archive` or `.Deleted`).
