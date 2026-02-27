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
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import java.util.TimeZone
import android.os.RemoteException
import java.io.PrintWriter
import java.io.StringWriter

data class SyncResult(val eventsProcessed: Int, val deletions: Int)

class SyncEngine(private val context: Context) {

    private val accountName = "MarkItHub Calendars"
    private val accountType = "com.waph1.markithub.calendars"
    private var rootFolder: Uri? = null
    private var taskRootFolder: Uri? = null
    
    private val syncMutex = Mutex()
    private val db = com.waph1.markithub.repository.SyncDatabase.getDatabase(context)
    private val dao = db.fileMetadataDao()
    private val childrenCache = mutableMapOf<Uri, List<DocumentInfo>>()

    fun setRootUri(uri: Uri) { rootFolder = uri }
    fun setTaskRootUri(uri: Uri) { taskRootFolder = uri }

    private fun logError(e: Throwable, context: String) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        SyncLogger.log(this.context, "calendar", "ERROR in $context: ${e.message}\n$sw")
    }

    suspend fun wipeAllAppData() = withContext(Dispatchers.IO) {
        SyncLogger.log(context, "calendar", "Wiping all app data and calendars...")
        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        context.contentResolver.delete(uri, "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(accountName, accountType))
        dao.getAll().forEach { dao.delete(it.filePath) }
        childrenCache.clear()
    }

    private val defaultColors = mapOf(
        "1" to 0xFF7986CB.toInt(), "2" to 0xFF33B679.toInt(), "3" to 0xFF8E24AA.toInt(),
        "4" to 0xFFE67C73.toInt(), "5" to 0xFFF6BF26.toInt(), "6" to 0xFFF4511E.toInt(),
        "7" to 0xFF039BE5.toInt(), "8" to 0xFF616161.toInt(), "9" to 0xFF3F51B5.toInt(),
        "10" to 0xFF0B8043.toInt(), "11" to 0xFFD60000.toInt()
    )

    suspend fun performSync(): SyncResult = syncMutex.withLock {
        return@withLock withContext(Dispatchers.IO) {
            try {
                if (rootFolder == null && taskRootFolder == null) throw IllegalStateException("No folder set")
                newCalendarsCreated.clear()
                childrenCache.clear()
                val calendarEventsData = mutableMapOf<String, Pair<MutableMap<Long, CalendarEvent>, MutableList<CalendarEvent>>>()
                val allUsedColors = mutableSetOf<Int>(); val foundFilePaths = mutableSetOf<String>()
                val seenIds = mutableSetOf<Long>(); var processed = 0; var deletions = 0

                if (rootFolder != null) {
                    val folders = listChildDocuments(rootFolder!!).filter { it.isDirectory && !it.name.startsWith(".") }
                    folders.groupBy { it.name.lowercase() }.forEach { (_, group) ->
                        if (group.size > 1) {
                            val oldest = group.minByOrNull { it.lastModified }!!
                            group.forEach { if (it != oldest) mergeFolders(it.uri, oldest.uri) }
                        }
                    }
                    // Refresh folders list after potential merges
                    listChildDocuments(rootFolder!!).filter { it.isDirectory && !it.name.startsWith(".") }.forEach { info ->
                        val name = info.name.trim()
                        val map = mutableMapOf<Long, CalendarEvent>(); val list = mutableListOf<CalendarEvent>()
                        scanFolderOptimized(name, info.uri, map, list, "", foundFilePaths, seenIds)
                        (map.values + list).forEach { it.color?.let { c -> allUsedColors.add(c) } }
                        calendarEventsData[name] = Pair(map, list)
                    }
                    SyncLogger.log(context, "calendar", "Scanned ${calendarEventsData.size} calendars.")
                }

                val taskEventsMap = mutableMapOf<Long, CalendarEvent>(); val newTaskEvents = mutableListOf<CalendarEvent>()
                if (taskRootFolder != null) {
                    scanTaskFolderOptimized(taskRootFolder!!, "", taskEventsMap, newTaskEvents, foundFilePaths, seenIds)
                    (taskEventsMap.values + newTaskEvents).forEach { event -> event.color?.let { allUsedColors.add(it) } }
                    SyncLogger.log(context, "calendar", "Scanned tasks: ${taskEventsMap.size} existing, ${newTaskEvents.size} new.")
                }
                
                cleanupOrphanedMetadata(foundFilePaths)
                val colorMap = manageColors(allUsedColors + getProviderUsedColors()); val androidCalendars = loadOurCalendars()

                if (rootFolder != null) {
                    calendarEventsData.forEach { (name, data) ->
                        getOrCreateCalendarId(name)?.let { id ->
                            val res = syncCalendar(name, id, data.first, data.second, colorMap)
                            processed += res.eventsProcessed; deletions += res.deletions
                        }
                    }
                    androidCalendars.keys.forEach { if (it != "Tasks" && it !in calendarEventsData.keys) { deleteCalendar(it); deletions++ } }
                }

                if (taskRootFolder != null) getOrCreateCalendarId("Tasks")?.let { id ->
                    val res = syncTasksCalendar(id, taskEventsMap, newTaskEvents, colorMap)
                    processed += res.eventsProcessed; deletions += res.deletions
                }

                SyncLogger.log(context, "calendar", "Sync complete. Total processed: $processed, Total deletions: $deletions")
                return@withContext SyncResult(processed, deletions)
            } catch (e: Exception) {
                logError(e, "performSync")
                throw e
            } finally {
                childrenCache.clear()
            }
        }
    }

    private suspend fun mergeFolders(source: Uri, target: Uri) {
        SyncLogger.log(context, "calendar", "Merging folder: $source -> $target")
        listChildDocuments(source).forEach { file ->
            if (file.isDirectory) getOrCreateFolder(target, file.name)?.let { mergeFolders(file.uri, it) }
            else try { 
                SyncLogger.log(context, "calendar", "Moving file during merge: ${file.name}")
                DocumentsContract.moveDocument(context.contentResolver, file.uri, source, target) 
                invalidateCache(source); invalidateCache(target)
            }
            catch (e: Exception) { 
                try { 
                    SyncLogger.log(context, "calendar", "Copying file during merge (fallback): ${file.name}")
                    DocumentsContract.copyDocument(context.contentResolver, file.uri, target)?.let { 
                        DocumentsContract.deleteDocument(context.contentResolver, file.uri)
                        invalidateCache(source); invalidateCache(target)
                    } 
                } catch (e2: Exception) { } 
            }
        }
        try { 
            DocumentsContract.deleteDocument(context.contentResolver, source)
        } catch (e: Exception) { }
    }

    private data class DocumentInfo(val uri: Uri, val name: String, val isDirectory: Boolean, val lastModified: Long)

    private fun invalidateCache(uri: Uri) {
        childrenCache.remove(uri)
    }

    private fun isFileAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                it.count > 0
            } ?: false
        } catch (e: Exception) { false }
    }

    private suspend fun cleanupOrphanedMetadata(foundPaths: Set<String>) {
        val allMeta = dao.getAll()
        var count = 0
        allMeta.forEach { if (!foundPaths.contains(it.filePath)) { 
            SyncLogger.log(context, "calendar", "Cleaning orphaned metadata: ${it.filePath}")
            dao.delete(it.filePath); count++ 
        } }
        if (count > 0) SyncLogger.log(context, "calendar", "Cleaned $count orphaned metadata entries.")
    }

    private fun listChildDocuments(parentUri: Uri): List<DocumentInfo> {
        if (childrenCache.containsKey(parentUri)) return childrenCache[parentUri]!!
        val result = mutableListOf<DocumentInfo>()
        try {
            val parentId = if (DocumentsContract.isDocumentUri(context, parentUri)) DocumentsContract.getDocumentId(parentUri) else DocumentsContract.getTreeDocumentId(parentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val lastModCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val isDir = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    result.add(DocumentInfo(DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idCol)), cursor.getString(nameCol), isDir, cursor.getLong(lastModCol)))
                }
            }
        } catch (e: Exception) { }
        childrenCache[parentUri] = result
        return result
    }

    private suspend fun scanFolderOptimized(calendarName: String, folderUri: Uri, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>, relativePath: String, foundPaths: MutableSet<String>, seenIds: MutableSet<Long>) {
        listChildDocuments(folderUri).forEach { info ->
            val currentRelPath = if (relativePath.isEmpty()) info.name else "$relativePath/${info.name}"
            val dbPath = "$calendarName/$currentRelPath"
            if (info.isDirectory && !info.name.startsWith(".")) scanFolderOptimized(calendarName, info.uri, map, newList, currentRelPath, foundPaths, seenIds)
            else if (info.name.endsWith(".md") && !info.name.contains(".sync-conflict-")) {
                foundPaths.add(dbPath); val meta = dao.getMetadata(dbPath)
                if (meta?.systemEventId != null && meta.lastModified == info.lastModified && !seenIds.contains(meta.systemEventId)) {
                    seenIds.add(meta.systemEventId); map[meta.systemEventId] = CalendarEvent(title = info.name.substringBeforeLast(".md"), systemEventId = meta.systemEventId, fileName = dbPath, sourceUri = info.uri.toString(), calendarName = calendarName, needsUpdate = false)
                    SyncLogger.log(context, "calendar", "File skipped (unchanged): $dbPath")
                    return@forEach
                }
                SyncLogger.log(context, "calendar", "Parsing file: $dbPath (Modified: ${info.lastModified})")
                parseFileOptimized(info.uri, info.name, calendarName, context)?.let { event ->
                    var finalEvent = event.copy(needsUpdate = true, fileName = dbPath, sourceUri = info.uri.toString())
                    val fileNameWithoutExt = info.name.substringBeforeLast(".md")
                    val cleanName = fileNameWithoutExt.substringBefore("~") // Strip conflict suffix
                    val datePart = cleanName.substringBefore("_", "")
                    val expectedTitle = if (cleanName.contains("_")) cleanName.substringAfter("_").replace("_", " ") else cleanName.replace("_", " ")
                    val parsedDate = try { LocalDate.parse(datePart) } catch (e: Exception) { null }
                    if (finalEvent.title != expectedTitle) finalEvent = finalEvent.copy(title = expectedTitle, needsUpdate = true)
                    if (parsedDate != null) { val start = finalEvent.start ?: LocalDateTime.now(); if (start.toLocalDate() != parsedDate) finalEvent = finalEvent.copy(start = parsedDate.atTime(start.toLocalTime()), needsUpdate = true) }
                    if (!YamlConverter.hasRequiredMetadata(info.uri, context)) finalEvent = finalEvent.copy(needsUpdate = true)
                    if (finalEvent.systemEventId != null && (seenIds.contains(finalEvent.systemEventId!!) || !verifyEventExists(finalEvent.systemEventId!!))) finalEvent = finalEvent.copy(systemEventId = null)
                    else if (finalEvent.systemEventId != null) seenIds.add(finalEvent.systemEventId!!)
                    if (finalEvent.systemEventId != null) { map[finalEvent.systemEventId!!] = finalEvent; dao.insert(com.waph1.markithub.repository.FileMetadata(dbPath, calendarName, info.lastModified, finalEvent.systemEventId)) }
                    else newList.add(finalEvent)
                }
            }
        }
    }

    private suspend fun scanTaskFolderOptimized(folderUri: Uri, relativePath: String, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>, foundPaths: MutableSet<String>, seenIds: MutableSet<Long>) {
        listChildDocuments(folderUri).forEach { info ->
            val pathForMeta = if (relativePath.isEmpty()) info.name else "$relativePath/${info.name}"
            val dbPath = "Tasks/$pathForMeta"
            if (info.isDirectory && !info.name.startsWith(".")) {
                scanTaskFolderOptimized(info.uri, pathForMeta, map, newList, foundPaths, seenIds)
            } else if (info.name.endsWith(".md") && !info.name.contains(".sync-conflict-")) {
                foundPaths.add(dbPath); val meta = dao.getMetadata(dbPath)
                if (meta?.systemEventId != null && meta.lastModified == info.lastModified && !seenIds.contains(meta.systemEventId)) {
                    seenIds.add(meta.systemEventId); map[meta.systemEventId] = CalendarEvent(title = "[] " + info.name.substringBeforeLast(".md"), systemEventId = meta.systemEventId, fileName = dbPath, sourceUri = info.uri.toString(), calendarName = "Tasks", needsUpdate = false)
                    SyncLogger.log(context, "calendar", "Task file skipped (unchanged): $dbPath")
                    return@forEach
                }
                SyncLogger.log(context, "calendar", "Parsing task file: $dbPath")
                parseTaskFileOptimized(info.uri, info.name, relativePath, context)?.let { event ->
                    var finalEvent = event.copy(needsUpdate = true, fileName = dbPath, sourceUri = info.uri.toString())
                    val cleanName = info.name.substringBeforeLast(".md").substringBefore("~")
                    val expectedTitle = "[] $cleanName"
                    if (finalEvent.title != expectedTitle) finalEvent = finalEvent.copy(title = expectedTitle, needsUpdate = true)
                    if (!YamlConverter.hasRequiredMetadata(info.uri, context)) finalEvent = finalEvent.copy(needsUpdate = true)
                    if (finalEvent.systemEventId != null && (seenIds.contains(finalEvent.systemEventId!!) || !verifyEventExists(finalEvent.systemEventId!!))) finalEvent = finalEvent.copy(systemEventId = null)
                    else if (finalEvent.systemEventId != null) seenIds.add(finalEvent.systemEventId!!)
                    if (finalEvent.systemEventId != null) { map[finalEvent.systemEventId!!] = finalEvent; dao.insert(com.waph1.markithub.repository.FileMetadata(dbPath, "Tasks", info.lastModified, finalEvent.systemEventId)) }
                    else newList.add(finalEvent)
                }
            }
        }
    }

    private fun verifyEventExists(id: Long): Boolean {
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()), null)?.use { return it.count > 0 }
        return false
    }

    private fun parseFileOptimized(uri: Uri, fileName: String, calendarName: String, context: Context): CalendarEvent? {
        return try { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }?.let { YamlConverter.parseMarkdown(it, fileName, calendarName) } } catch (e: Exception) { null }
    }

    private fun parseTaskFileOptimized(uri: Uri, fileName: String, relativePath: String, context: Context): CalendarEvent? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return null
            if (!Regex("(?m)^(reminder|start):").containsMatchIn(content)) return null
            val event = YamlConverter.parseMarkdown(content, fileName, "Tasks")
            if (event.start == null) return null
            val cleanName = fileName.substringBeforeLast(".md").substringBefore("~")
            event.copy(title = if (event.title.startsWith("[] ")) event.title else "[] $cleanName", end = event.start.plusMinutes(10), fileName = "Tasks/" + (if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"))
        } catch (e: Exception) { null }
    }

    private fun ensureFullEvent(event: CalendarEvent): CalendarEvent {
        if (event.needsUpdate || event.start != null) return event
        val root = if (event.calendarName == "Tasks") taskRootFolder else rootFolder; if (root == null) return event
        val path = event.fileName?.let { if (it.startsWith("${event.calendarName}/")) it.removePrefix("${event.calendarName}/") else if (it.startsWith("Tasks/")) it.removePrefix("Tasks/") else it } ?: return event
        val uri = findDocumentInPath(root, path) ?: return event
        return if (event.calendarName == "Tasks") parseTaskFileOptimized(uri, path.split("/").last(), path.substringBeforeLast("/", ""), context) ?: event
        else parseFileOptimized(uri, path.split("/").last(), event.calendarName, context) ?: event
    }

    private fun normalizeTask(event: CalendarEvent): CalendarEvent {
        val start = event.start ?: LocalDateTime.now()
        return event.copy(title = if (event.title.startsWith("[] ")) event.title else "[] " + event.title, end = start.plusMinutes(10))
    }

            private suspend fun syncTasksCalendar(calendarId: Long, fileEventsMap: Map<Long, CalendarEvent>, newFileEvents: List<CalendarEvent>, colorMap: Map<String, Int>): SyncResult {

                val providerEvents = loadProviderEvents(calendarId, colorMap); var processed = 0; var deletions = 0; val ops = ArrayList<ContentProviderOperation>()

                val totalOps = providerEvents.size + newFileEvents.size

                var currentOp = 0

                SyncLogger.log(context, "calendar", "Starting synchronization phase for Tasks (ID: $calendarId). Total items to evaluate: $totalOps")

                

                providerEvents.forEach { (id, pEvent) ->

                    currentOp++

                    val fEvent = fileEventsMap[id]

                    SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Evaluating provider task: ${pEvent.title}")

                    

                    if (pEvent.title.startsWith("[x] ", true) || pEvent.title.startsWith("[X] ", true)) {

                        if (fEvent != null) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Completing task: ${fEvent.title}"); completeTask(fEvent) }; ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build()); deletions++

                    } else if (pEvent.deleted) {

                        if (fEvent != null) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Removing reminder from: ${fEvent.title}"); removeReminderFromFile(fEvent); processed++ }; ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build())

                    } else if (pEvent.dirty) {

                        SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Provider task dirty: ${pEvent.title}")

                        if (fEvent != null && fEvent.needsUpdate) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Updating task file (both dirty/needs update): ${pEvent.title}"); saveTaskFile(providerEventToModel(pEvent, null, "Tasks"), null); ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValue(CalendarContract.Events.DIRTY, 0).build()) }

                        else if (fEvent != null) mergeProviderChangesIntoFile(pEvent, fEvent, "Tasks", calendarId)

                        else { val adopted = normalizeTask(providerEventToModel(pEvent, null, "Tasks")); SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Saving new task from calendar: ${adopted.title}"); saveTaskFile(adopted, null); ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValues(eventToContentValues(adopted, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }).build()) }

                        if (ops.none { it.uri.lastPathSegment == id.toString() }) ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValue(CalendarContract.Events.DIRTY, 0).build())

                        processed++

                    } else if (fEvent == null) {

                        if (!newCalendarsCreated.contains(calendarId)) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Deleting provider task (file missing): ${pEvent.title}"); ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build()); deletions++ }

                    } else if (fEvent.needsUpdate) {

                        SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Updating provider task (file changed): ${fEvent.title}"); ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValues(eventToContentValues(fEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }).build()); processed++

                    }

                    if (ops.size >= 50) { SyncLogger.log(context, "calendar", "Applying batch of ${ops.size} Task operations..."); applyBatch(ops); ops.clear() }

                }

                fileEventsMap.keys.forEach { if (!providerEvents.containsKey(it) && !newCalendarsCreated.contains(calendarId)) { SyncLogger.log(context, "calendar", "Removing reminder (orphaned): ${fileEventsMap[it]!!.title}"); removeReminderFromFile(ensureFullEvent(fileEventsMap[it]!!)); deletions++ } }

                if (ops.isNotEmpty()) { SyncLogger.log(context, "calendar", "Applying final batch of ${ops.size} Task operations..."); applyBatch(ops) }

                

                        newFileEvents.forEach { rawEvent ->

                

                            currentOp++

                

                            val event = if (rawEvent.start == null) {

                

                                if (rawEvent.isAllDay) {

                

                                    val today = LocalDate.now()

                

                                    rawEvent.copy(start = today.atStartOfDay())

                

                                } else {

                

                                    rawEvent.copy(start = LocalDateTime.now())

                

                                }

                

                            } else rawEvent

                

                            SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Syncing new file to Tasks: ${event.title}")

                    insertProviderEvent(event, calendarId)?.let { newId -> 

                        try {

                            saveTaskFile(event.copy(systemEventId = newId), event.fileName)

                            processed++

                        } catch (e: Exception) {

                            SyncLogger.log(context, "calendar", "Failed to create task, rolling back: ${e.message}");

                            deleteProviderEvent(newId)

                        }

                    } 

                }

                return SyncResult(processed, deletions)

            }

            private suspend fun syncCalendar(calendarName: String, calendarId: Long, fileEventsMap: Map<Long, CalendarEvent>, newFileEvents: List<CalendarEvent>, colorMap: Map<String, Int>): SyncResult {
                val providerEvents = loadProviderEvents(calendarId, colorMap); var processed = 0; var deletions = 0; val ops = ArrayList<ContentProviderOperation>()
                val totalOps = providerEvents.size + newFileEvents.size
                var currentOp = 0
                SyncLogger.log(context, "calendar", "Starting synchronization phase for calendar '$calendarName' (ID: $calendarId). Total items to evaluate: $totalOps")
                
                providerEvents.forEach { (id, pEvent) ->
                    currentOp++
                    val fEvent = fileEventsMap[id]
                    SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Evaluating provider event: ${pEvent.title}")
                    
                    if (pEvent.deleted) { 
                        if (fEvent != null) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Deleting file (provider marked deleted): ${fEvent.title}"); deleteFile(fEvent); deletions++ }; ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build()) 
                    } else if (pEvent.dirty) {
                        SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Provider event dirty: ${pEvent.title}")
                        if (fEvent != null && fEvent.needsUpdate) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Updating file (both dirty/needs update): ${pEvent.title}"); saveToFile(providerEventToModel(pEvent, null, calendarName)); ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValue(CalendarContract.Events.DIRTY, 0).build()) }
                        else if (fEvent != null) mergeProviderChangesIntoFile(pEvent, fEvent, calendarName, calendarId)
                        else { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Saving new file from calendar (dirty): ${pEvent.title}"); saveToFile(providerEventToModel(pEvent, null, calendarName)) }
                        ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValue(CalendarContract.Events.DIRTY, 0).build()); processed++
                    } else if (fEvent == null) { 
                        if (!newCalendarsCreated.contains(calendarId)) { SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Deleting provider event (file missing): ${pEvent.title}"); ops.add(ContentProviderOperation.newDelete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).build()); deletions++ }
                    } else if (fEvent.needsUpdate) { 
                        SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Updating provider event (file changed): ${fEvent.title}"); ops.add(ContentProviderOperation.newUpdate(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build()).withValues(eventToContentValues(fEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }).build()); processed++ 
                    }
                    if (ops.size >= 50) { SyncLogger.log(context, "calendar", "Applying batch of ${ops.size} operations for $calendarName..."); applyBatch(ops); ops.clear() }
                }
                fileEventsMap.keys.forEach { if (!providerEvents.containsKey(it) && !newCalendarsCreated.contains(calendarId)) { SyncLogger.log(context, "calendar", "Deleting orphaned file: ${fileEventsMap[it]!!.title}"); deleteFile(ensureFullEvent(fileEventsMap[it]!!)); deletions++ } }
                if (ops.isNotEmpty()) { SyncLogger.log(context, "calendar", "Applying final batch of ${ops.size} operations for $calendarName..."); applyBatch(ops) }
                
                        newFileEvents.forEach { rawEvent ->
                            currentOp++
                            val event = if (rawEvent.start == null) {
                                if (rawEvent.isAllDay) {
                                    val today = LocalDate.now()
                                    rawEvent.copy(start = today.atStartOfDay())
                                } else {
                                    rawEvent.copy(start = LocalDateTime.now())
                                }
                            } else rawEvent
                            SyncLogger.log(context, "calendar", "[$currentOp/$totalOps] Syncing new file to calendar: ${event.title}")
                            insertProviderEvent(event, calendarId)?.let { newId -> 
                        try {
                            saveToFile(event.copy(systemEventId = newId))
                            processed++ 
                        } catch (e: Exception) {
                            SyncLogger.log(context, "calendar", "Failed to create event file, rolling back: ${e.message}");
                            deleteProviderEvent(newId)
                        }
                    } 
                }
                return SyncResult(processed, deletions)
            }
    private fun deleteProviderEvent(id: Long) {
        try {
            context.contentResolver.delete(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(id.toString()).build(), null, null)
        } catch (e: Exception) {}
    }

    private suspend fun mergeProviderChangesIntoFile(pEvent: CalendarProviderEvent, fEvent: CalendarEvent, calendarName: String, calendarId: Long) {
        val originalFileName = fEvent.fileName ?: return
        val root = if (calendarName == "Tasks") taskRootFolder else rootFolder; if (root == null) return
        val path = if (originalFileName.startsWith("$calendarName/")) originalFileName.removePrefix("$calendarName/") else if (originalFileName.startsWith("Tasks/")) originalFileName.removePrefix("Tasks/") else originalFileName
        
        var fileUri = fEvent.sourceUri?.let { Uri.parse(it) }
        if (fileUri != null && !isFileAccessible(fileUri)) {
            fileUri = null
        }

        if (fileUri == null) {
            fileUri = findDocumentInPath(root, path)
        }

        if (fileUri == null) {
            SyncLogger.log(context, "calendar", "Could not find file for $originalFileName")
            return
        }

        try {
            val content = context.contentResolver.openInputStream(fileUri)?.use { it.bufferedReader().readText() } ?: run {
                SyncLogger.log(context, "calendar", "Could not read content for $originalFileName")
                return
            }
            val currentEvent = YamlConverter.parseMarkdown(content, originalFileName.split("/").last(), calendarName)
            var updatedEvent = providerEventToModel(pEvent, originalFileName, calendarName).copy(body = currentEvent.body, metadata = currentEvent.metadata, tags = currentEvent.tags)
            
            SyncLogger.log(context, "calendar", "Merging provider changes for ${pEvent.title}. Provider end: ${updatedEvent.end}, File end was: ${currentEvent.end}")
            
            if (calendarName == "Tasks") { updatedEvent = normalizeTask(updatedEvent); context.contentResolver.update(asSyncAdapter(CalendarContract.Events.CONTENT_URI).buildUpon().appendPath(pEvent.id.toString()).build(), eventToContentValues(updatedEvent, calendarId).apply { put(CalendarContract.Events.DIRTY, 0) }, null, null) }
            val start = updatedEvent.start ?: LocalDateTime.now(); val base = if (updatedEvent.recurrenceRule != null) sanitizeFilename(updatedEvent.title) else "${start.toLocalDate()}_${sanitizeFilename(updatedEvent.title)}"; val expected = "$base.md"; 
            
            val calendarFolder = getOrCreateFolder(root, calendarName) ?: root
            val parentUri = findParentUri(root, path) ?: calendarFolder
            
            var uniqueName = expected
            if (expected != originalFileName.split("/").last()) {
                 if (findDocumentInPath(parentUri, uniqueName) != null) { var c = 1; while (findDocumentInPath(parentUri, "$base ($c).md") != null) c++; uniqueName = "$base ($c).md" }
            } else {
                uniqueName = originalFileName.split("/").last()
            }
            
            val finalFileName = if (originalFileName.contains("/")) originalFileName.substringBeforeLast("/") + "/" + uniqueName else "$calendarName/$uniqueName"
            
            // Atomic Write (replaces existing fileUri)
            val newUri = safeWrite(parentUri, uniqueName, YamlConverter.toMarkdown(updatedEvent), fileUri)
            
            if (newUri != null) {
                 if (finalFileName != originalFileName) {
                     dao.delete(originalFileName)
                 }
                 updateMetadataForFile(newUri, finalFileName, calendarName, updatedEvent.systemEventId)
            }
        } catch (e: Exception) {
            logError(e, "mergeProviderChangesIntoFile for $originalFileName")
        }
    }

    private fun applyBatch(ops: ArrayList<ContentProviderOperation>) { if (ops.isNotEmpty()) try { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops) } catch (e: Exception) {} }

    private suspend fun saveTaskFile(event: CalendarEvent, originalFileName: String?) {
        val root = taskRootFolder ?: return
        var fileName: String
        var parentUri: Uri?
        var fileUri: Uri? = null

        val pathInTaskFolder = originalFileName?.removePrefix("Tasks/")
        
        if (pathInTaskFolder != null) {
             parentUri = findParentUri(root, pathInTaskFolder)
             fileName = pathInTaskFolder.split("/").last()
             fileUri = findDocumentInPath(root, pathInTaskFolder)
        } else if (event.sourceUri != null) {
            fileUri = Uri.parse(event.sourceUri)
            if (!isFileAccessible(fileUri)) fileUri = null
            // If sourceUri exists, we need its parent to write safely (rename logic)
            // But getting parent from Uri is hard. We rely on path if possible.
            // If we only have Uri, safeWrite might fail if we can't get parent.
            // But here, if we have sourceUri, we assume it's in our tree?
            // Actually, if we have event.fileName, we prefer that.
            val fName = event.fileName?.removePrefix("Tasks/")
            if (fName != null) {
                parentUri = findParentUri(root, fName)
                fileName = fName.split("/").last()
            } else {
                 // Fallback: Treat as new file in Inbox if we can't locate parent
                 parentUri = getOrCreateFolder(root, "Inbox") ?: root
                 val base = sanitizeFilename(if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title)
                 fileName = "$base.md"
            }
        } else {
             parentUri = getOrCreateFolder(root, "Inbox") ?: root
             val base = sanitizeFilename(if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title)
             fileName = "$base.md"
        }

        if (parentUri == null) return

        // Name conflict resolution
        val base = sanitizeFilename(if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title)
        var desired = "$base.md"
        
        // If we are renaming (title changed) or creating new
        if (desired != fileName || fileUri == null) {
             if (findDocumentInPath(parentUri, desired) != null && findDocumentInPath(parentUri, desired) != fileUri) {
                 var c = 1
                 while (findDocumentInPath(parentUri, "$base ($c).md") != null) c++
                 desired = "$base ($c).md"
             }
        }
        
        val newUri = safeWrite(parentUri, desired, YamlConverter.toMarkdown(event), fileUri)
        
        if (newUri != null) {
             // Reconstruct path for metadata
             // If parentUri is root/Inbox
             val parentPath = if (parentUri == root) "" else "Inbox/" // Simplification, might be wrong if deep folder
             // Better: we can't easily reconstruct path from Uri.
             // But we know where we put it.
             
             // If we preserved the folder structure:
             val newPath = if (pathInTaskFolder != null && pathInTaskFolder.contains("/")) {
                 pathInTaskFolder.substringBeforeLast("/") + "/" + desired
             } else if (parentUri == getOrCreateFolder(root, "Inbox")) {
                 "Inbox/$desired"
             } else {
                 desired // Root?
             }
             
             if (originalFileName != null && originalFileName != "Tasks/$newPath") {
                 dao.delete(originalFileName)
             }
             updateMetadataForFile(newUri, "Tasks/$newPath", "Tasks", event.systemEventId)
        }
    }

    private suspend fun completeTask(event: CalendarEvent) {
        val root = taskRootFolder ?: return; val rel = event.fileName?.removePrefix("Tasks/") ?: return; val src = findDocumentInPath(root, rel) ?: return
        try {
            val content = context.contentResolver.openInputStream(src)?.use { it.bufferedReader().readText() } ?: return; val archive = getOrCreateFolder(root, ".Archive") ?: return; var target = archive; val parts = rel.split("/")
            for (i in 0 until parts.size - 1) target = getOrCreateFolder(target, parts[i]) ?: return
            DocumentsContract.createDocument(context.contentResolver, target, "text/markdown", parts.last())?.let { 
                context.contentResolver.openOutputStream(it, "wt")?.use { out -> out.write(YamlConverter.removeCalendarData(content).toByteArray()) }
                invalidateCache(target)
            }; 
            DocumentsContract.deleteDocument(context.contentResolver, src)
            findParentUri(root, rel)?.let { invalidateCache(it) }
            dao.delete(event.fileName!!)
        } catch (e: Exception) {}
    }

    private suspend fun removeReminderFromFile(event: CalendarEvent) {
        val root = taskRootFolder ?: return; val path = event.fileName?.removePrefix("Tasks/") ?: return; val uri = findDocumentInPath(root, path) ?: return
        try { val content = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return; context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(YamlConverter.removeCalendarData(content).toByteArray()) }; updateMetadataForFile(uri, event.fileName!!, "Tasks", null) } catch (e: Exception) {}
    }

    private suspend fun saveToFile(event: CalendarEvent) {
        val root = rootFolder ?: return; val folder = getOrCreateFolder(root, event.calendarName) ?: return; val start = event.start ?: LocalDateTime.now()
        val target = if (event.recurrenceRule != null) getOrCreateFolder(folder, "_Recurring") else { val yearF = getOrCreateFolder(folder, start.year.toString()); if (yearF != null) getOrCreateFolder(yearF, String.format("%02d", start.monthValue)) else null }
        val targetFolder = target ?: return; val base = if (event.recurrenceRule != null) sanitizeFilename(event.title) else "${start.toLocalDate()}_${sanitizeFilename(event.title)}"; var expected = "$base.md"
        val pathInCal = event.fileName?.removePrefix("${event.calendarName}/")
        
        var uri: Uri? = null
        if (event.sourceUri != null) {
            val pUri = Uri.parse(event.sourceUri)
            if (isFileAccessible(pUri)) uri = pUri
        }
        if (uri == null && pathInCal != null) {
            uri = findDocumentInPath(folder, pathInCal)
        }
        
        if (uri != null) {
            val currentParent = if (pathInCal != null) findParentUri(folder, pathInCal) else folder
            if (currentParent != targetFolder || expected != (pathInCal?.split("/")?.last() ?: "")) {
                var unique = expected; if (findDocumentInPath(targetFolder, unique) != null && findDocumentInPath(targetFolder, unique) != uri) { var c = 1; while (findDocumentInPath(targetFolder, "$base ($c).md") != null) c++; unique = "$base ($c).md" }
                try {
                    val movedUri = if (currentParent != targetFolder) {
                        val m = DocumentsContract.moveDocument(context.contentResolver, uri, currentParent!!, targetFolder) 
                        if (m != null) { invalidateCache(currentParent); invalidateCache(targetFolder) }
                        m ?: uri
                    } else uri
                    
                    val renamedUri = DocumentsContract.renameDocument(context.contentResolver, movedUri, unique)
                    if (renamedUri != null) {
                        uri = renamedUri
                        invalidateCache(targetFolder)
                    } else {
                        // Fallback if rename returns null (shouldn't happen on success) but keep using movedUri
                        uri = movedUri 
                    }
                    expected = unique
                    if (event.fileName != null) dao.delete(event.fileName!!)
                } catch (e: Exception) { 
                    if (event.sourceUri == null) {
                        uri = DocumentsContract.createDocument(context.contentResolver, targetFolder, "text/markdown", expected)
                        if (uri != null) invalidateCache(targetFolder)
                    }
                }
            }
        } else {
             uri = DocumentsContract.createDocument(context.contentResolver, targetFolder, "text/markdown", expected)
             if (uri != null) invalidateCache(targetFolder)
        }
        uri?.let { 
            // SyncLogger.log(context, "calendar", "Writing to file: $it (expected: $expected)") // Commented out to avoid spam, uncomment if needed
            context.contentResolver.openOutputStream(it, "wt")?.use { out -> out.write(YamlConverter.toMarkdown(event).toByteArray()) }; updateMetadataForFile(it, "${event.calendarName}/${if (event.recurrenceRule != null) "" else "${start.year}/${String.format("%02d", start.monthValue)}/"}$expected", event.calendarName, event.systemEventId) 
        }
    }

    private suspend fun deleteFile(event: CalendarEvent) {
        val root = rootFolder ?: return; val folder = findDocumentInPath(root, event.calendarName) ?: return; val start = event.start ?: LocalDateTime.now()
        val target = if (event.recurrenceRule != null) findDocumentInPath(folder, "_Recurring") else { val yearF = findDocumentInPath(folder, start.year.toString()); if (yearF != null) findDocumentInPath(yearF, String.format("%02d", start.monthValue)) else null }
        val pathInCal = event.fileName?.removePrefix("${event.calendarName}/") ?: ""
        val src = findDocumentInPath(target ?: return, pathInCal.split("/").last()) ?: return; val deleted = getOrCreateFolder(root, ".Deleted") ?: return
        try { 
            context.contentResolver.openInputStream(src)?.use { input -> 
                DocumentsContract.createDocument(context.contentResolver, deleted, "text/markdown", "${event.calendarName}_${System.currentTimeMillis()}_${pathInCal.replace("/", "_")}")?.let { 
                    context.contentResolver.openOutputStream(it)?.use { output -> input.copyTo(output) }
                    invalidateCache(deleted)
                } 
            }
            DocumentsContract.deleteDocument(context.contentResolver, src)
            invalidateCache(target)
            dao.delete(event.fileName!!) 
        } catch (e: Exception) {}
    }

    private suspend fun updateMetadataForFile(uri: Uri, filePath: String, calendarName: String, systemId: Long?) {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) dao.insert(com.waph1.markithub.repository.FileMetadata(filePath, calendarName, it.getLong(0), systemId)) }
    }

    private fun findDocumentInPath(root: Uri, path: String): Uri? {
        var curr = root; try { path.split("/").filter { it.isNotEmpty() }.forEach { p -> curr = listChildDocuments(curr).find { it.name == p }?.uri ?: return null } } catch (e: Exception) { return null }; return curr
    }

    private fun findParentUri(root: Uri, path: String): Uri? {
        val parts = path.split("/").filter { it.isNotEmpty() }; return if (parts.size <= 1) root else findDocumentInPath(root, parts.dropLast(1).joinToString("/"))
    }

    private fun getOrCreateFolder(parent: Uri, name: String): Uri? {
        val exist = listChildDocuments(parent).find { it.name == name && it.isDirectory }; return exist?.uri ?: try { 
            val u = DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            if (u != null) invalidateCache(parent)
            u
        } catch (e: Exception) { null }
    }

    private fun getProviderUsedColors(): Set<Int> {
        val colors = mutableSetOf<Int>(); val keys = mutableSetOf<String>()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.EVENT_COLOR, CalendarContract.Events.EVENT_COLOR_KEY), "${CalendarContract.Events.ACCOUNT_NAME} = ? AND ${CalendarContract.Events.ACCOUNT_TYPE} = ?", arrayOf(accountName, accountType), null)?.use { while (it.moveToNext()) { if (!it.isNull(0)) colors.add(it.getInt(0)); if (!it.isNull(1)) keys.add(it.getString(1)) } }
        if (keys.isNotEmpty()) context.contentResolver.query(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?", arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()), null)?.use { while (it.moveToNext()) if (it.getString(0) in keys) colors.add(it.getInt(1)) }
        return colors
    }

    private fun manageColors(used: Set<Int>): Map<String, Int> {
        val map = defaultColors.toMutableMap(); val exist = mutableMapOf<String, Int>()
        context.contentResolver.query(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?", arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()), null)?.use { while (it.moveToNext()) exist[it.getString(0)] = it.getInt(1) }
        exist.forEach { (k, c) -> if (!defaultColors.containsKey(k) && c !in used) context.contentResolver.delete(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_KEY} = ?", arrayOf(accountName, accountType, k)) else map[k] = c }
        defaultColors.forEach { (k, v) -> if (k !in exist) insertColor(k, v) }
        used.forEach { c -> if (!map.containsValue(c)) { val k = "custom_$c"; insertColor(k, c); map[k] = c } }; return map
    }

    private fun insertColor(key: String, color: Int) {
        context.contentResolver.insert(asSyncAdapter(CalendarContract.Colors.CONTENT_URI), ContentValues().apply { put(CalendarContract.Colors.ACCOUNT_NAME, accountName); put(CalendarContract.Colors.ACCOUNT_TYPE, accountType); put(CalendarContract.Colors.COLOR_KEY, key); put(CalendarContract.Colors.COLOR, color); put(CalendarContract.Colors.COLOR_TYPE, CalendarContract.Colors.TYPE_EVENT) })
    }

    private fun loadOurCalendars(): Map<String, Long> {
        val map = mutableMapOf<String, Long>(); context.contentResolver.query(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(accountName, accountType), null)?.use { while (it.moveToNext()) map[it.getString(1)] = it.getLong(0) }; return map
    }

    private fun loadProviderEvents(calendarId: Long, colorMap: Map<String, Int>): Map<Long, CalendarProviderEvent> {
        val events = mutableMapOf<Long, CalendarProviderEvent>()
        context.contentResolver.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI), arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED, CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EVENT_COLOR, CalendarContract.Events.EVENT_COLOR_KEY), "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()), null)?.use { while (it.moveToNext()) { val id = it.getLong(0); val dtStart = it.getLong(2); val dtEnd = it.getLong(3); val duration = if (it.isNull(4)) null else it.getString(4); val colorKey = if (it.isNull(13)) null else it.getString(13); val color = (if (it.isNull(12)) null else it.getInt(12)) ?: if (colorKey != null) colorMap[colorKey] else null; events[id] = CalendarProviderEvent(id, it.getString(1) ?: "", dtStart, dtEnd, duration, it.getString(5) ?: "", it.getInt(6) == 1, it.getInt(7) == 1, it.getString(8), it.getInt(9) == 1, it.getString(10), it.getString(11), color, loadAttendees(id), loadReminders(id)) } }
        return events
    }

    private fun loadAttendees(id: Long): List<String> { val list = mutableListOf<String>(); context.contentResolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL), "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(id.toString()), null)?.use { while (it.moveToNext()) it.getString(0)?.let { email -> list.add(email) } }; return list }
    private fun loadReminders(id: Long): List<Int> { val list = mutableListOf<Int>(); context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null)?.use { while (it.moveToNext()) list.add(it.getInt(0)) }; return list }

    private fun insertProviderEvent(event: CalendarEvent, calendarId: Long): Long? {
        val uri = context.contentResolver.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), eventToContentValues(event, calendarId)); val id = uri?.lastPathSegment?.toLongOrNull() ?: return null; syncAttendees(id, event.attendees); syncReminders(id, event.reminders); return id
    }

    private fun syncAttendees(id: Long, attendees: List<String>) {
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(id.toString()))
        attendees.forEach { email -> context.contentResolver.insert(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), ContentValues().apply { put(CalendarContract.Attendees.EVENT_ID, id); put(CalendarContract.Attendees.ATTENDEE_EMAIL, email); put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE); put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED); put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED) }) }
    }

    private fun syncReminders(id: Long, reminders: List<Int>) {
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
        reminders.forEach { min -> context.contentResolver.insert(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), ContentValues().apply { put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, min); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT) }) }
    }

    private fun parseDuration(duration: String): Duration {
        try {
            if (duration.startsWith("P")) {
                var d = duration
                if ((d.contains("H") || d.contains("M") || d.contains("S")) && !d.contains("T")) {
                    d = d.replace("P", "PT")
                }
                return Duration.parse(d)
            } else {
                val numericPart = duration.filter { it.isDigit() }
                val seconds = numericPart.toLongOrNull()
                return if (seconds != null) Duration.ofSeconds(seconds) else Duration.ofHours(1)
            }
        } catch (e: Exception) {
            return Duration.ofHours(1)
        }
    }

    private fun eventToContentValues(event: CalendarEvent, calendarId: Long): ContentValues {
        val zone = if (event.isAllDay) ZoneId.of("UTC") else (event.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault())
        val start = event.start ?: LocalDateTime.now()
        val dtStart: Long
        val dtEnd: Long
        
        if (event.isAllDay) { 
            val date = start.toLocalDate()
            dtStart = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            dtEnd = date.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() 
        } else { 
            dtStart = start.atZone(zone).toInstant().toEpochMilli()
            dtEnd = (event.end ?: start.plusHours(1)).atZone(zone).toInstant().toEpochMilli() 
        }
        
        val values = ContentValues().apply { 
            put(CalendarContract.Events.DTSTART, dtStart)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.body)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
            event.color?.let { put(CalendarContract.Events.EVENT_COLOR, it) }
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.recurrenceRule?.let { put(CalendarContract.Events.RRULE, it) } 
        }
        if (event.recurrenceRule != null) { 
            val dur = Duration.between(start, event.end ?: start.plusHours(1))
            values.put(CalendarContract.Events.DURATION, "P" + dur.toSeconds() + "S") 
        } else { 
            values.put(CalendarContract.Events.DTEND, dtEnd) 
        }
        return values
    }

    private fun providerEventToModel(p: CalendarProviderEvent, file: String?, cal: String): CalendarEvent {
        val zone = if (p.allDay) ZoneId.of("UTC") else (p.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(p.dtStart).atZone(zone).toLocalDateTime()
        var end = if (p.dtEnd > 0) Instant.ofEpochMilli(p.dtEnd).atZone(zone).toLocalDateTime() else start.plusHours(1)
        if (p.duration != null && (p.dtEnd <= 0 || p.recurrenceRule != null)) { try { val dur = parseDuration(p.duration!!); end = start.plus(dur) } catch (e: Exception) { } }
        return CalendarEvent(title = p.title, start = start, end = end, isAllDay = p.allDay, location = p.location, attendees = p.attendees, reminders = p.reminders, timezone = p.timezone, color = p.color, body = p.description, systemEventId = p.id, recurrenceRule = p.recurrenceRule, calendarName = cal, fileName = file)
    }

    private val newCalendarsCreated = mutableSetOf<Long>()

    private fun getOrCreateCalendarId(name: String): Long? {
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.NAME} = ?", arrayOf(accountName, name), null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        val v = ContentValues().apply { put(CalendarContract.Calendars.ACCOUNT_NAME, accountName); put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType); put(CalendarContract.Calendars.NAME, name); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "App: $name"); put(CalendarContract.Calendars.CALENDAR_COLOR, android.graphics.Color.BLUE); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER); put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName); put(CalendarContract.Calendars.SYNC_EVENTS, 1); put(CalendarContract.Calendars.VISIBLE, 1) }
        val id = context.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), v)?.lastPathSegment?.toLongOrNull()
        if (id != null) newCalendarsCreated.add(id); return id
    }

    private fun safeWrite(parentUri: Uri, fileName: String, content: String, originalUri: Uri?): Uri? {
        try {
            val tempName = ".tmp_${System.currentTimeMillis()}_$fileName"
            val tempUri = DocumentsContract.createDocument(context.contentResolver, parentUri, "text/markdown", tempName) ?: return null
            
            context.contentResolver.openOutputStream(tempUri, "wt")?.use { it.write(content.toByteArray()) } ?: run {
                DocumentsContract.deleteDocument(context.contentResolver, tempUri)
                return null
            }
            
            if (originalUri != null) {
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, originalUri)
                } catch (e: Exception) {
                    // If delete fails, we might have a duplicate. 
                    // But we proceed to rename the temp file anyway.
                }
            }
            
            val renamed = DocumentsContract.renameDocument(context.contentResolver, tempUri, fileName)
            return renamed ?: tempUri
        } catch (e: Exception) {
            logError(e, "safeWrite for $fileName")
            return null
        }
    }

    private fun asSyncAdapter(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType).build()
    private fun deleteCalendar(name: String) { context.contentResolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?", arrayOf(accountName, accountType, name)) }
    private fun sanitizeFilename(name: String): String { val res = Regex("[<>:\"/\\\\|?*]"); val s = res.replace(name, "-").trim().trimEnd('.'); return if (s.isEmpty()) "Untitled" else s }
    
    fun ensureAccountExists() {
        val accountManager = android.accounts.AccountManager.get(context)
        val account = android.accounts.Account(accountName, accountType)
        if (accountManager.addAccountExplicitly(account, null, null)) {
            android.content.ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
            android.content.ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
            SyncLogger.log(context, "calendar", "Created MarkItHub Calendars sync account")
        }
    }
}

data class CalendarProviderEvent(val id: Long, val title: String, val dtStart: Long, val dtEnd: Long, val duration: String?, val description: String, val dirty: Boolean, val deleted: Boolean, val recurrenceRule: String?, val allDay: Boolean, val location: String?, val timezone: String?, val color: Int?, val attendees: List<String> = emptyList(), val reminders: List<Int> = emptyList())
