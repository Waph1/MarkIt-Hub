package com.waph1.markithub.util

import android.content.*
import android.net.Uri
import android.provider.CalendarContract
import android.provider.DocumentsContract
import com.waph1.markithub.model.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import android.os.RemoteException

data class SyncResult(val eventsProcessed: Int, val deletions: Int)

class SyncEngine(private val context: Context) {

    private val accountName = "CalendarApp"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL
    private var rootFolder: Uri? = null
    private var taskRootFolder: Uri? = null
    
    private val syncMutex = Mutex()
    private val db = com.waph1.markithub.repository.SyncDatabase.getDatabase(context)
    private val dao = db.fileMetadataDao()

    fun setRootUri(uri: Uri) {
        rootFolder = uri
    }

    fun setTaskRootUri(uri: Uri) {
        taskRootFolder = uri
    }

    suspend fun wipeAllAppData() = withContext(Dispatchers.IO) {
        SyncLogger.log(context, "Wiping all app data and calendars for a clean start...")
        // 1. Delete all calendars for this account
        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        context.contentResolver.delete(uri, "${CalendarContract.Calendars.ACCOUNT_NAME} = ?", arrayOf(accountName))
        
        // 2. Clear metadata DB
        dao.getAll().forEach { dao.delete(it.filePath) }
        SyncLogger.log(context, "Wipe complete.")
    }

    private val defaultColors = mapOf(
        "1" to 0xFF7986CB.toInt(), // Lavender
        "2" to 0xFF33B679.toInt(), // Sage
        "3" to 0xFF8E24AA.toInt(), // Grape
        "4" to 0xFFE67C73.toInt(), // Flamingo
        "5" to 0xFFF6BF26.toInt(), // Banana
        "6" to 0xFFF4511E.toInt(), // Tangerine
        "7" to 0xFF039BE5.toInt(), // Peacock
        "8" to 0xFF616161.toInt(), // Graphite
        "9" to 0xFF3F51B5.toInt(), // Blueberry
        "10" to 0xFF0B8043.toInt(), // Basil
        "11" to 0xFFD60000.toInt()  // Tomato
    )

    suspend fun performSync(): SyncResult = syncMutex.withLock {
        return@withLock withContext(Dispatchers.IO) {
            if (rootFolder == null && taskRootFolder == null) throw IllegalStateException("No folder set")
            
            newCalendarsCreated.clear()
            val calendarEventsData = mutableMapOf<String, Pair<MutableMap<Long, CalendarEvent>, MutableList<CalendarEvent>>>()
            val allUsedColors = mutableSetOf<Int>()
            val foundFilePaths = mutableSetOf<String>()
            val seenSystemIds = mutableSetOf<Long>()
            var totalProcessed = 0
            var totalDeletions = 0

            if (rootFolder != null) {
                val calendarFolders = listChildDocuments(rootFolder!!)
                    .filter { it.isDirectory && !it.name.startsWith(".") }

                for (folderInfo in calendarFolders) {
                    val name = folderInfo.name
                    val map = mutableMapOf<Long, CalendarEvent>()
                    val list = mutableListOf<CalendarEvent>()
                    scanFolderOptimized(name, folderInfo.uri, map, list, "", foundFilePaths, seenSystemIds)
                    (map.values + list).forEach { event -> event.color?.let { allUsedColors.add(it) } }
                    calendarEventsData[name] = Pair(map, list)
                }
            }

            val taskEventsMap = mutableMapOf<Long, CalendarEvent>()
            val newTaskEvents = mutableListOf<CalendarEvent>()
            if (taskRootFolder != null) {
                scanTaskFolderOptimized(taskRootFolder!!, "", taskEventsMap, newTaskEvents, foundFilePaths, seenSystemIds)
                (taskEventsMap.values + newTaskEvents).forEach { event -> event.color?.let { allUsedColors.add(it) } }
            }
            
            // Cleanup metadata for files not found during this scan
            cleanupOrphanedMetadata(foundFilePaths)

            val providerColors = getProviderUsedColors()
            allUsedColors.addAll(providerColors)
            val colorMap = manageColors(allUsedColors)
            val androidCalendars = loadOurCalendars()

            if (rootFolder != null) {
                val calendarFolders = listChildDocuments(rootFolder!!)
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                 
                for (folderInfo in calendarFolders) {
                    val name = folderInfo.name
                    val calendarId = getOrCreateCalendarId(name) ?: continue
                    val data = calendarEventsData[name] ?: continue
                    val (fileEventsMap, newFileEvents) = data
                    
                    val result = syncCalendar(name, calendarId, fileEventsMap, newFileEvents, colorMap)
                    totalProcessed += result.eventsProcessed
                    totalDeletions += result.deletions
                }
                val folderNames = calendarFolders.map { it.name }.toSet()
                for (name in androidCalendars.keys) {
                    if (name != "Tasks" && name !in folderNames) {
                        deleteCalendar(name)
                        totalDeletions++
                    }
                }
            }

            if (taskRootFolder != null) {
                val tasksCalendarId = getOrCreateCalendarId("Tasks")
                if (tasksCalendarId != null) {
                    val result = syncTasksCalendar(tasksCalendarId, taskEventsMap, newTaskEvents, colorMap)
                    totalProcessed += result.eventsProcessed
                    totalDeletions += result.deletions
                }
            }

            return@withContext SyncResult(totalProcessed, totalDeletions)
        }
    }

    private data class DocumentInfo(val uri: Uri, val name: String, val isDirectory: Boolean, val lastModified: Long)

    private suspend fun cleanupOrphanedMetadata() {
        val allMeta = dao.getAll()
        for (meta in allMeta) {
            val root = if (meta.calendarName == "Tasks") taskRootFolder else rootFolder
            if (root == null || findDocumentInPath(root, meta.filePath.removePrefix("Tasks/").removePrefix("${meta.calendarName}/")) == null) {
                dao.delete(meta.filePath)
            }
        }
    }

    private suspend fun cleanupOrphanedMetadata(foundPaths: Set<String>) {
        val allMeta = dao.getAll()
        for (meta in allMeta) {
            if (!foundPaths.contains(meta.filePath)) {
                dao.delete(meta.filePath)
            }
        }
    }

    private fun listChildDocuments(parentUri: Uri): List<DocumentInfo> {
        val result = mutableListOf<DocumentInfo>()
        return try {
            val parentId = if (DocumentsContract.isDocumentUri(context, parentUri)) {
                DocumentsContract.getDocumentId(parentUri)
            } else {
                DocumentsContract.getTreeDocumentId(parentUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val lastModCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    val lastMod = cursor.getLong(lastModCol)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, id)
                    result.add(DocumentInfo(uri, name, isDir, lastMod))
                }
            }
            result
        } catch (e: Exception) {
            SyncLogger.log(context, "Error listing children: ${e.message}")
            result
        }
    }

    private suspend fun scanFolderOptimized(calendarName: String, folderUri: Uri, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>, relativePath: String, foundPaths: MutableSet<String>, seenIds: MutableSet<Long>) {
        SyncLogger.log(context, "Scanning folder: $calendarName/$relativePath")
        listChildDocuments(folderUri).forEach { info ->
            val currentRelPath = if (relativePath.isEmpty()) info.name else "$relativePath/${info.name}"
            if (info.isDirectory) {
                scanFolderOptimized(calendarName, info.uri, map, newList, currentRelPath, foundPaths, seenIds)
            } else if (info.name.endsWith(".md")) {
                if (info.name.contains(".sync-conflict-")) return@forEach
                
                val dbPath = "$calendarName/$currentRelPath"
                foundPaths.add(dbPath)
                val meta = dao.getMetadata(dbPath)
                
                if (meta != null && meta.lastModified == info.lastModified && meta.systemEventId != null) {
                    if (seenIds.contains(meta.systemEventId)) {
                        SyncLogger.log(context, "Duplicate ID ${meta.systemEventId} found in cache for $currentRelPath. Forcing re-parse as new.")
                    } else {
                        seenIds.add(meta.systemEventId)
                        val cachedEvent = CalendarEvent(
                            title = info.name.substringBeforeLast(".md"),
                            systemEventId = meta.systemEventId,
                            fileName = currentRelPath,
                            calendarName = calendarName,
                            needsUpdate = false
                        )
                        map[meta.systemEventId] = cachedEvent
                        return@forEach
                    }
                }
                
                parseFileOptimized(info.uri, info.name, calendarName)?.let { event ->
                    var finalEvent = event.copy(needsUpdate = true, fileName = currentRelPath)
                    
                    if (finalEvent.systemEventId != null) {
                        if (seenIds.contains(finalEvent.systemEventId!!)) {
                            SyncLogger.log(context, "Duplicate ID ${finalEvent.systemEventId} found in file $currentRelPath. Treating as new.")
                            finalEvent = finalEvent.copy(systemEventId = null)
                        } else if (!verifyEventExists(finalEvent.systemEventId!!)) {
                            SyncLogger.log(context, "Old ID ${finalEvent.systemEventId} for ${info.name} is invalid. Re-inserting.")
                            finalEvent = finalEvent.copy(systemEventId = null)
                        } else {
                            seenIds.add(finalEvent.systemEventId!!)
                        }
                    }

                    if (finalEvent.systemEventId != null) {
                        map[finalEvent.systemEventId!!] = finalEvent
                        dao.insert(com.waph1.markithub.repository.FileMetadata(dbPath, calendarName, info.lastModified, finalEvent.systemEventId))
                    } else {
                        newList.add(finalEvent)
                    }
                }
            }
        }
    }

    private suspend fun scanTaskFolderOptimized(folderUri: Uri, relativePath: String, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>, foundPaths: MutableSet<String>, seenIds: MutableSet<Long>) {
        listChildDocuments(folderUri).forEach { info ->
            val pathForMeta = if (relativePath.isEmpty()) info.name else "$relativePath/${info.name}"
            if (info.isDirectory) {
                if (info.name != ".Archive" && info.name != ".Deleted" && info.name != "Pinned") {
                    scanTaskFolderOptimized(info.uri, pathForMeta, map, newList, foundPaths, seenIds)
                } else if (info.name == "Pinned") {
                    scanTaskFolderOptimized(info.uri, relativePath, map, newList, foundPaths, seenIds)
                }
            } else if (info.name.endsWith(".md")) {
                if (info.name.contains(".sync-conflict-")) return@forEach

                val dbPath = "Tasks/$pathForMeta"
                foundPaths.add(dbPath)
                val meta = dao.getMetadata(dbPath)
                if (meta != null && meta.lastModified == info.lastModified && meta.systemEventId != null) {
                    if (seenIds.contains(meta.systemEventId)) {
                        SyncLogger.log(context, "Duplicate ID ${meta.systemEventId} found in cache for $pathForMeta. Forcing re-parse as new.")
                    } else {
                        seenIds.add(meta.systemEventId)
                        val cachedEvent = CalendarEvent(
                            title = "[] " + info.name.substringBeforeLast(".md"),
                            systemEventId = meta.systemEventId,
                            fileName = pathForMeta,
                            calendarName = "Tasks",
                            needsUpdate = false
                        )
                        map[meta.systemEventId] = cachedEvent
                        return@forEach
                    }
                }
                
                parseTaskFileOptimized(info.uri, info.name, relativePath)?.let { event ->
                    var finalEvent = event.copy(needsUpdate = true, fileName = pathForMeta)
                    
                    if (finalEvent.systemEventId != null) {
                        if (seenIds.contains(finalEvent.systemEventId!!)) {
                            SyncLogger.log(context, "Duplicate ID ${finalEvent.systemEventId} found in file $pathForMeta. Treating as new.")
                            finalEvent = finalEvent.copy(systemEventId = null)
                        } else if (!verifyEventExists(finalEvent.systemEventId!!)) {
                            SyncLogger.log(context, "Old ID ${finalEvent.systemEventId} for ${info.name} is invalid. Re-inserting.")
                            finalEvent = finalEvent.copy(systemEventId = null)
                        } else {
                            seenIds.add(finalEvent.systemEventId!!)
                        }
                    }

                    if (finalEvent.systemEventId != null) {
                        map[finalEvent.systemEventId!!] = finalEvent
                        dao.insert(com.waph1.markithub.repository.FileMetadata(dbPath, "Tasks", info.lastModified, finalEvent.systemEventId))
                    } else {
                        newList.add(finalEvent)
                    }
                }
            }
        }
    }

    private fun verifyEventExists(id: Long): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events._ID} = ?"
        val selectionArgs = arrayOf(id.toString())
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    private fun parseFileOptimized(uri: Uri, fileName: String, calendarName: String): CalendarEvent? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            content?.let { YamlConverter.parseMarkdown(it, fileName, calendarName) }
        } catch (e: Exception) { null }
    }

    private fun parseTaskFileOptimized(uri: Uri, fileName: String, relativePath: String): CalendarEvent? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return null
            val event = YamlConverter.parseMarkdown(content, fileName, "Tasks")
            if (!content.contains("reminder:") && !content.contains("start:")) return null
            val title = fileName.substringBeforeLast(".md")
            event.copy(
                title = if (event.title.startsWith("[] ")) event.title else "[] $title",
                end = (event.start ?: LocalDateTime.now()).plusMinutes(10),
                fileName = if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"
            )
        } catch (e: Exception) { null }
    }

    private fun ensureFullEvent(event: CalendarEvent): CalendarEvent {
        if (event.needsUpdate || event.start != null) return event
        
        // It's a stub, re-parse
        val root = if (event.calendarName == "Tasks") taskRootFolder else rootFolder
        val path = event.fileName ?: return event
        val uri = root?.let { findDocumentInPath(it, path) } ?: return event
        
        return if (event.calendarName == "Tasks") {
             parseTaskFileOptimized(uri, path.split("/").last(), path.substringBeforeLast("/", "")) ?: event
        } else {
             parseFileOptimized(uri, path.split("/").last(), event.calendarName) ?: event
        }
    }

    private fun normalizeTask(event: CalendarEvent): CalendarEvent {
        var updated = event
        if (!updated.title.startsWith("[] ")) {
            updated = updated.copy(title = "[] " + updated.title)
        }
        val start = updated.start ?: LocalDateTime.now()
        val properEnd = start.plusMinutes(10)
        if (updated.end != properEnd) {
            updated = updated.copy(end = properEnd)
        }
        return updated
    }

    private suspend fun syncTasksCalendar(calendarId: Long, fileEventsMap: Map<Long, CalendarEvent>, newFileEvents: List<CalendarEvent>, colorMap: Map<String, Int>): SyncResult {
        val providerEvents = loadProviderEvents(calendarId, colorMap)
        var processed = 0
        var deletions = 0
        val ops = ArrayList<ContentProviderOperation>()

        providerEvents.forEach { (id, pEvent) ->
            val fEvent = fileEventsMap[id]
            if (pEvent.title.startsWith("[x] ", true) || pEvent.title.startsWith("[X] ", true)) {
                if (fEvent != null) completeTask(fEvent)
                ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())
                deletions++
            } else if (pEvent.deleted) {
                if (fEvent != null) { removeReminderFromFile(fEvent); processed++ }
                ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())
            } else if (pEvent.dirty) {
                if (fEvent != null && fEvent.fileName != null) {
                    mergeProviderChangesIntoFile(pEvent, fEvent.fileName!!, "Tasks", calendarId)
                } else {
                    // Adoption logic: New event from provider (e.g. user created it in Calendar App)
                    val adopted = normalizeTask(providerEventToModel(pEvent, null, "Tasks"))
                    saveTaskFile(adopted, null)
                    
                    // Update the provider back with normalized values (10 mins, [] prefix)
                    val values = eventToContentValues(adopted, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }
                    ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build())
                        .withValues(values).build())
                }
                
                if (!ops.any { it.uri.lastPathSegment == id.toString() }) {
                    ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build())
                        .withValue(CalendarContract.Events.DIRTY, 0).build())
                }
                processed++
            } else if (fEvent == null) {
                if (!newCalendarsCreated.contains(calendarId)) {
                    ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())
                    deletions++
                }
            } else {
                if (fEvent.needsUpdate) {
                    val values = eventToContentValues(fEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }
                    ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValues(values).build())
                    processed++
                }
            }
            if (ops.size >= 50) { applyBatch(ops); ops.clear() }
        }

        fileEventsMap.keys.forEach { if (!providerEvents.containsKey(it)) { 
            if (!newCalendarsCreated.contains(calendarId)) {
                val fullEvent = ensureFullEvent(fileEventsMap[it]!!)
                removeReminderFromFile(fullEvent)
                deletions++ 
            }
        } }
        applyBatch(ops)
        newFileEvents.forEach { event ->
            insertProviderEvent(event, calendarId)?.let { newId ->
                saveTaskFile(event.copy(systemEventId = newId), event.fileName)
                processed++
            }
        }
        return SyncResult(processed, deletions)
    }

    private suspend fun syncCalendar(calendarName: String, calendarId: Long, fileEventsMap: Map<Long, CalendarEvent>, newFileEvents: List<CalendarEvent>, colorMap: Map<String, Int>): SyncResult {
        val providerEvents = loadProviderEvents(calendarId, colorMap)
        var processed = 0
        var deletions = 0
        val ops = ArrayList<ContentProviderOperation>()

        providerEvents.forEach { (id, pEvent) ->
            val fEvent = fileEventsMap[id]
            if (pEvent.deleted) {
                if (fEvent != null) { deleteFile(fEvent); deletions++ }
                ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())
            } else if (pEvent.dirty) {
                if (fEvent != null && fEvent.fileName != null) {
                    mergeProviderChangesIntoFile(pEvent, fEvent.fileName!!, calendarName, calendarId)
                } else {
                    val updated = providerEventToModel(pEvent, null, calendarName)
                    saveToFile(updated)
                }
                ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build())
                    .withValue(CalendarContract.Events.DIRTY, 0).build())
                processed++
            } else if (fEvent == null) {
                if (!newCalendarsCreated.contains(calendarId)) {
                    ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())
                    deletions++
                }
            } else {
                if (fEvent.needsUpdate) {
                    val values = eventToContentValues(fEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }
                    ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValues(values).build())
                    processed++
                }
            }
            if (ops.size >= 50) { applyBatch(ops); ops.clear() }
        }

        fileEventsMap.keys.forEach { if (!providerEvents.containsKey(it)) { 
            if (!newCalendarsCreated.contains(calendarId)) {
                val fullEvent = ensureFullEvent(fileEventsMap[it]!!)
                deleteFile(fullEvent)
                deletions++ 
            }
        } }
        applyBatch(ops)
        newFileEvents.forEach { event ->
            insertProviderEvent(event, calendarId)?.let { newId ->
                saveToFile(event.copy(systemEventId = newId))
                processed++
            }
        }
        return SyncResult(processed, deletions)
    }

    private fun mergeProviderChangesIntoFile(pEvent: CalendarProviderEvent, originalFileName: String, calendarName: String, calendarId: Long) {
        val root = if (calendarName == "Tasks") taskRootFolder else rootFolder
        if (root == null) return
        val fileUri = findDocumentInPath(root, originalFileName) ?: return
        
        try {
            val content = context.contentResolver.openInputStream(fileUri)?.use { it.bufferedReader().readText() } ?: return
            val currentEvent = YamlConverter.parseMarkdown(content, originalFileName.split("/").last(), calendarName)
            
            // Merge metadata from provider, keep body from file
            var updatedEvent = providerEventToModel(pEvent, originalFileName, calendarName).copy(
                body = currentEvent.body,
                metadata = currentEvent.metadata,
                tags = currentEvent.tags
            )
            
            // Enforce task rules during merge if applicable
            if (calendarName == "Tasks") {
                updatedEvent = normalizeTask(updatedEvent)
                
                // Push normalized metadata (10 mins, [] prefix) back to provider
                val values = eventToContentValues(updatedEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }
                context.contentResolver.update(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(pEvent.id.toString()).build(), values, null, null)
            }
            
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { 
                it.write(YamlConverter.toMarkdown(updatedEvent).toByteArray()) 
            }
            updateMetadataForFile(fileUri, if (calendarName == "Tasks") "Tasks/$originalFileName" else "$calendarName/$originalFileName", calendarName, updatedEvent.systemEventId)
        } catch (e: Exception) {
            SyncLogger.log(context, "Merge error for $originalFileName: ${e.message}")
        }
    }

    private fun applyBatch(ops: ArrayList<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        try { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops) } catch (e: Exception) { SyncLogger.log(context, "Batch error: ${e.message}") }
    }

    private fun saveTaskFile(event: CalendarEvent, originalFileName: String?) {
        val root = taskRootFolder ?: return
        var fileUri: Uri?
        var fileName: String

        if (originalFileName != null) {
            fileUri = findDocumentInPath(root, originalFileName) ?: return
            fileName = originalFileName.split("/").last()
        } else {
            val inboxUri = getOrCreateFolder(root, "Inbox") ?: root
            val cleanTitle = sanitizeFilename(if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title)
            fileName = "$cleanTitle.md"
            if (findDocumentInPath(inboxUri, fileName) != null) {
                var counter = 1
                while (findDocumentInPath(inboxUri, "$cleanTitle ($counter).md") != null) counter++
                fileName = "$cleanTitle ($counter).md"
            }
            fileUri = DocumentsContract.createDocument(context.contentResolver, inboxUri, "text/markdown", fileName) ?: return
        }

        try {
            if (originalFileName != null) {
                val desiredName = "${sanitizeFilename(if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title)}.md"
                if (desiredName != fileName) {
                    val parent = findParentUri(root, originalFileName) ?: root
                    if (findDocumentInPath(parent, desiredName) == null) {
                        DocumentsContract.renameDocument(context.contentResolver, fileUri!!, desiredName)
                        // Metadata will be updated by updateMetadataForFile below with the new URI
                        // But the path in DB needs to change too
                        val oldPath = "Tasks/$originalFileName"
                        val newRelPath = if (originalFileName.contains("/")) originalFileName.substringBeforeLast("/") + "/" + desiredName else desiredName
                        val newPath = "Tasks/$newRelPath"
                        
                        kotlinx.coroutines.runBlocking {
                            dao.getMetadata(oldPath)?.let {
                                dao.delete(oldPath)
                                dao.insert(it.copy(filePath = newPath))
                            }
                        }
                    }
                }
            }
            context.contentResolver.openOutputStream(fileUri!!, "wt")?.use { it.write(YamlConverter.toMarkdown(event).toByteArray()) }
            
            // Update metadata after write
            val finalRelPath = if (originalFileName != null) {
                 if (originalFileName.contains("/")) originalFileName.substringBeforeLast("/") + "/" + fileName else fileName
            } else {
                 "Inbox/$fileName"
            }
            updateMetadataForFile(fileUri!!, "Tasks/$finalRelPath", "Tasks", event.systemEventId)

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun completeTask(event: CalendarEvent) {
        val root = taskRootFolder ?: return
        val relativePath = event.fileName ?: return
        val sourceUri = findDocumentInPath(root, relativePath) ?: return
        try {
            val content = context.contentResolver.openInputStream(sourceUri)?.use { it.bufferedReader().readText() } ?: return
            val newContent = YamlConverter.removeCalendarData(content)
            val archiveRoot = getOrCreateFolder(root, ".Archive") ?: return
            var targetDir = archiveRoot
            val parts = relativePath.split("/")
            for (i in 0 until parts.size - 1) targetDir = getOrCreateFolder(targetDir, parts[i]) ?: return
            val targetUri = DocumentsContract.createDocument(context.contentResolver, targetDir, "text/markdown", parts.last())
            if (targetUri != null) {
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { it.write(newContent.toByteArray()) }
                DocumentsContract.deleteDocument(context.contentResolver, sourceUri)
                // Remove from sync metadata as it's no longer a synced task
                kotlinx.coroutines.runBlocking { dao.delete("Tasks/$relativePath") }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun removeReminderFromFile(event: CalendarEvent) {
        val root = taskRootFolder ?: return
        val path = event.fileName ?: return
        val uri = findDocumentInPath(root, path) ?: return
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return
            val newContent = YamlConverter.removeCalendarData(content)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(newContent.toByteArray()) }
            // Metadata: update it since content changed, but systemId might be gone/invalid
            updateMetadataForFile(uri, "Tasks/$path", "Tasks", null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveToFile(event: CalendarEvent) {
        val root = rootFolder ?: return
        val calendarFolder = getOrCreateFolder(root, event.calendarName) ?: return
        val start = event.start ?: LocalDateTime.now()
        val targetFolder = if (event.recurrenceRule != null) getOrCreateFolder(calendarFolder, "_Recurring") else {
            val yearF = getOrCreateFolder(calendarFolder, start.year.toString()) ?: return
            getOrCreateFolder(yearF, String.format("%02d", start.monthValue))
        } ?: return
        val sanitized = sanitizeFilename(event.title)
        val expected = if (event.recurrenceRule != null) "$sanitized.md" else "${start.toLocalDate()}_$sanitized.md"
        var uri = findDocumentInPath(targetFolder, expected)
        
        val oldRelPath = if (event.fileName != null) "${event.calendarName}/${event.fileName}" else null

        if (event.fileName != null && event.fileName != expected) {
            findDocumentInPath(targetFolder, event.fileName!!)?.let { oldUri ->
                if (uri == null) { 
                    DocumentsContract.renameDocument(context.contentResolver, oldUri, expected)
                    uri = oldUri 
                    // Update metadata path in DB
                    kotlinx.coroutines.runBlocking {
                         dao.getMetadata("${event.calendarName}/${event.fileName}")?.let {
                             dao.delete("${event.calendarName}/${event.fileName}")
                             dao.insert(it.copy(filePath = "${event.calendarName}/$expected"))
                         }
                    }
                }
            }
        }
        if (uri == null) uri = DocumentsContract.createDocument(context.contentResolver, targetFolder, "text/markdown", expected)
        uri?.let { 
            context.contentResolver.openOutputStream(it, "wt")?.use { out -> out.write(YamlConverter.toMarkdown(event).toByteArray()) }
            updateMetadataForFile(it, "${event.calendarName}/$expected", event.calendarName, event.systemEventId)
        }
    }

    private fun deleteFile(event: CalendarEvent) {
        val root = rootFolder ?: return
        val calFolder = findDocumentInPath(root, event.calendarName) ?: return
        val start = event.start ?: LocalDateTime.now()
        val targetFolder = if (event.recurrenceRule != null) findDocumentInPath(calFolder, "_Recurring") else {
            findDocumentInPath(calFolder, start.year.toString())?.let { findDocumentInPath(it, String.format("%02d", start.monthValue)) }
        } ?: return
        val sourceUri = findDocumentInPath(targetFolder, event.fileName ?: "") ?: return
        val deletedFolder = getOrCreateFolder(root, ".Deleted") ?: return
        val targetUri = DocumentsContract.createDocument(context.contentResolver, deletedFolder, "text/markdown", "${event.calendarName}_${System.currentTimeMillis()}_${event.fileName}")
        if (targetUri != null) {
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input -> context.contentResolver.openOutputStream(targetUri)?.use { output -> input.copyTo(output) } }
                DocumentsContract.deleteDocument(context.contentResolver, sourceUri)
                // Remove from metadata
                kotlinx.coroutines.runBlocking { dao.delete("${event.calendarName}/${event.fileName}") }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun updateMetadataForFile(uri: Uri, filePath: String, calendarName: String, systemId: Long?) {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val lastMod = cursor.getLong(0)
                kotlinx.coroutines.runBlocking {
                    dao.insert(com.waph1.markithub.repository.FileMetadata(filePath, calendarName, lastMod, systemId))
                }
            }
        }
    }

    private fun findDocumentInPath(rootUri: Uri, path: String): Uri? {
        var current = rootUri
        path.split("/").filter { it.isNotEmpty() }.forEach { part ->
            current = listChildDocuments(current).find { it.name == part }?.uri ?: return null
        }
        return current
    }

    private fun findParentUri(rootUri: Uri, path: String): Uri? {
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.size <= 1) return rootUri
        return findDocumentInPath(rootUri, parts.dropLast(1).joinToString("/"))
    }

    private fun getOrCreateFolder(parentUri: Uri, name: String): Uri? {
        val existing = listChildDocuments(parentUri).find { it.name == name && it.isDirectory }
        if (existing != null) return existing.uri
        return try { DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name) } catch (e: Exception) { null }
    }

    private fun getProviderUsedColors(): Set<Int> {
        val colors = mutableSetOf<Int>()
        val keys = mutableSetOf<String>()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.EVENT_COLOR, CalendarContract.Events.EVENT_COLOR_KEY), "${CalendarContract.Events.ACCOUNT_NAME} = ? AND ${CalendarContract.Events.ACCOUNT_TYPE} = ?", arrayOf(accountName, accountType), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) colors.add(cursor.getInt(0))
                if (!cursor.isNull(1)) keys.add(cursor.getString(1))
            }
        }
        if (keys.isNotEmpty()) {
            context.contentResolver.query(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?", arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()), null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(0) in keys) colors.add(cursor.getInt(1))
            }
        }
        return colors
    }

    private fun manageColors(usedColors: Set<Int>): Map<String, Int> {
        val map = defaultColors.toMutableMap()
        val existing = mutableMapOf<String, Int>()
        context.contentResolver.query(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?", arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) existing[cursor.getString(0)] = cursor.getInt(1)
        }
        existing.forEach { (key, color) -> if (!defaultColors.containsKey(key) && color !in usedColors) context.contentResolver.delete(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_KEY} = ?", arrayOf(accountName, accountType, key)) else map[key] = color }
        defaultColors.forEach { (k, v) -> if (k !in existing) insertColor(k, v) }
        usedColors.forEach { c -> if (!map.containsValue(c)) { val k = "custom_$c"; insertColor(k, c); map[k] = c } }
        return map
    }

    private fun insertColor(key: String, color: Int) {
        val values = ContentValues().apply { put(CalendarContract.Colors.ACCOUNT_NAME, accountName); put(CalendarContract.Colors.ACCOUNT_TYPE, accountType); put(CalendarContract.Colors.COLOR_KEY, key); put(CalendarContract.Colors.COLOR, color); put(CalendarContract.Colors.COLOR_TYPE, CalendarContract.Colors.TYPE_EVENT) }
        context.contentResolver.insert(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), values)
    }

    private fun loadOurCalendars(): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        context.contentResolver.query(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(accountName, accountType), null)?.use { cursor ->
            while (cursor.moveToNext()) map[cursor.getString(1)] = cursor.getLong(0)
        }
        return map
    }

    private fun loadProviderEvents(calendarId: Long, colorMap: Map<String, Int>): Map<Long, CalendarProviderEvent> {
        val events = mutableMapOf<Long, CalendarProviderEvent>()
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED, CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EVENT_COLOR, CalendarContract.Events.EVENT_COLOR_KEY)
        context.contentResolver.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI), projection, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val cInt = if (cursor.isNull(11)) null else cursor.getInt(11)
                val cKey = if (cursor.isNull(12)) null else cursor.getString(12)
                val color = cInt ?: cKey?.let { colorMap[it] }
                events[id] = CalendarProviderEvent(id, cursor.getString(1) ?: "", cursor.getLong(2), cursor.getLong(3), cursor.getString(4) ?: "", cursor.getInt(5) == 1, cursor.getInt(6) == 1, cursor.getString(7), cursor.getInt(8) == 1, cursor.getString(9), cursor.getString(10), color, loadAttendees(id), loadReminders(id))
            }
        }
        return events
    }

    private fun loadAttendees(eventId: Long): List<String> {
        val list = mutableListOf<String>()
        context.contentResolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL), "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { list.add(it) }
        }
        return list
    }

    private fun loadReminders(eventId: Long): List<Int> {
        val list = mutableListOf<Int>()
        context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) list.add(cursor.getInt(0))
        }
        return list
    }

    private fun insertProviderEvent(event: CalendarEvent, calendarId: Long): Long? {
        val values = eventToContentValues(event, calendarId)
        val uri = context.contentResolver.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values)
        val id = uri?.lastPathSegment?.toLongOrNull()
        if (id != null) { syncAttendees(id, event.attendees); syncReminders(id, event.reminders) }
        return id
    }

    private fun syncAttendees(eventId: Long, attendees: List<String>) {
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()))
        attendees.forEach { email ->
            val v = ContentValues().apply { put(CalendarContract.Attendees.EVENT_ID, eventId); put(CalendarContract.Attendees.ATTENDEE_EMAIL, email); put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE); put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED); put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED) }
            context.contentResolver.insert(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), v)
        }
    }

    private fun syncReminders(eventId: Long, reminders: List<Int>) {
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        reminders.forEach { min ->
            val v = ContentValues().apply { put(CalendarContract.Reminders.EVENT_ID, eventId); put(CalendarContract.Reminders.MINUTES, min); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT) }
            context.contentResolver.insert(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), v)
        }
    }

    private fun eventToContentValues(event: CalendarEvent, calendarId: Long): ContentValues {
        val zone = event.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        val start = event.start ?: LocalDateTime.now()
        val end = event.end ?: start.plusHours(1)
        return ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.body)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
            event.color?.let { put(CalendarContract.Events.EVENT_COLOR, it) }
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.recurrenceRule?.let { put(CalendarContract.Events.RRULE, it) }
        }
    }

    private fun providerEventToModel(pEvent: CalendarProviderEvent, existingFileName: String?, calendarName: String): CalendarEvent {
        val zone = pEvent.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(pEvent.dtStart).atZone(zone).toLocalDateTime()
        val end = if (pEvent.dtEnd > 0) Instant.ofEpochMilli(pEvent.dtEnd).atZone(zone).toLocalDateTime() else start.plusHours(1)
        return CalendarEvent(title = pEvent.title, start = start, end = end, isAllDay = pEvent.allDay, location = pEvent.location, attendees = pEvent.attendees, reminders = pEvent.reminders, timezone = pEvent.timezone, color = pEvent.color, body = pEvent.description, systemEventId = pEvent.id, recurrenceRule = pEvent.recurrenceRule, calendarName = calendarName, fileName = existingFileName)
    }

    private val newCalendarsCreated = mutableSetOf<Long>()

    private fun getOrCreateCalendarId(name: String): Long? {
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.NAME} = ?", arrayOf(accountName, name), null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        val v = ContentValues().apply { put(CalendarContract.Calendars.ACCOUNT_NAME, accountName); put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType); put(CalendarContract.Calendars.NAME, name); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "App: $name"); put(CalendarContract.Calendars.CALENDAR_COLOR, android.graphics.Color.BLUE); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER); put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName); put(CalendarContract.Calendars.SYNC_EVENTS, 1) }
        val id = context.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v)?.lastPathSegment?.toLongOrNull()
        if (id != null) newCalendarsCreated.add(id)
        return id
    }

    private fun asSyncAdapter(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType).build()

    private fun deleteCalendar(name: String) {
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?", arrayOf(accountName, accountType, name))
    }

    private fun sanitizeFilename(name: String): String {
        val res = Regex("[<>:\"/\\\\|?*]")
        var s = res.replace(name, "-").trim().trimEnd('.')
        return if (s.isEmpty()) "Untitled" else s
    }
}

data class CalendarProviderEvent(val id: Long, val title: String, val dtStart: Long, val dtEnd: Long, val description: String, val dirty: Boolean, val deleted: Boolean, val recurrenceRule: String?, val allDay: Boolean, val location: String?, val timezone: String?, val color: Int?, val attendees: List<String> = emptyList(), val reminders: List<Int> = emptyList())
