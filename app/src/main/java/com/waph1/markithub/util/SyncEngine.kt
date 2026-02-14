package com.waph1.markithub.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import androidx.documentfile.provider.DocumentFile
import com.waph1.markithub.model.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

data class SyncResult(val eventsProcessed: Int, val deletions: Int)

class SyncEngine(private val context: Context) {

    private val accountName = "CalendarApp"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL
    private var rootFolder: DocumentFile? = null
    private var taskRootFolder: DocumentFile? = null

    fun setRootUri(uri: Uri) {
        rootFolder = DocumentFile.fromTreeUri(context, uri)
    }

    fun setTaskRootUri(uri: Uri) {
        taskRootFolder = DocumentFile.fromTreeUri(context, uri)
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

    suspend fun performSync(): SyncResult = withContext(Dispatchers.IO) {
        if (rootFolder == null && taskRootFolder == null) throw IllegalStateException("No folder set")
        
        // Data structures to hold scanned events to avoid re-scanning
        val calendarEventsData = mutableMapOf<String, Pair<MutableMap<Long, CalendarEvent>, MutableList<CalendarEvent>>>()
        val allUsedColors = mutableSetOf<Int>()
        var totalProcessed = 0
        var totalDeletions = 0

        // --- Main Calendar Sync ---
        if (rootFolder != null) {
            // 1. Scan all folders in root (excluding hidden)
            val calendarFolders = rootFolder!!.listFiles()
                .filter { it.isDirectory && it.name?.startsWith(".") == false }

            // Scan files and collect colors
            for (folder in calendarFolders) {
                val name = folder.name ?: continue
                val map = mutableMapOf<Long, CalendarEvent>()
                val list = mutableListOf<CalendarEvent>()
                
                scanFolder(name, folder, map, list)
                
                // Collect colors from files
                (map.values + list).forEach { event -> 
                    event.color?.let { allUsedColors.add(it) } 
                }
                
                calendarEventsData[name] = Pair(map, list)
            }
        }

        // --- Tasks Sync Preparation ---
        val taskEventsMap = mutableMapOf<Long, CalendarEvent>()
        val newTaskEvents = mutableListOf<CalendarEvent>()
        if (taskRootFolder != null) {
            scanTaskFolder(taskRootFolder!!, "", taskEventsMap, newTaskEvents)
             (taskEventsMap.values + newTaskEvents).forEach { event ->
                event.color?.let { allUsedColors.add(it) }
            }
        }

        // 2. Collect colors used in Provider (to avoid deleting used custom colors)
        val providerColors = getProviderUsedColors()
        allUsedColors.addAll(providerColors)

        // 3. Manage Colors (Add missing, Remove unused custom)
        val colorMap = manageColors(allUsedColors)
        
        // 4. Get all calendars in Android for our account
        val androidCalendars = loadOurCalendars()
        val androidCalendarNames = androidCalendars.keys

        // 5. Sync existing folders using pre-scanned data
        if (rootFolder != null) {
             val calendarFolders = rootFolder!!.listFiles()
                .filter { it.isDirectory && it.name?.startsWith(".") == false }
             
             for (folder in calendarFolders) {
                val name = folder.name ?: continue
                val calendarId = getOrCreateCalendarId(name) ?: continue
                val (fileEventsMap, newFileEvents) = calendarEventsData[name]!!
                
                val result = syncCalendar(name, calendarId, folder, fileEventsMap, newFileEvents, colorMap)
                totalProcessed += result.eventsProcessed
                totalDeletions += result.deletions
            }
             // 6. Handle calendars that exist in Android but have no folder
            val folderNames = calendarFolders.mapNotNull { it.name }.toSet()
            for (name in androidCalendarNames) {
                if (name != "Tasks" && name !in folderNames) {
                    deleteCalendar(name)
                    totalDeletions++ // Optional metric
                }
            }
        }

        // 7. Sync Tasks
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

    private suspend fun syncTasksCalendar(
        calendarId: Long,
        fileEventsMap: MutableMap<Long, CalendarEvent>,
        newFileEvents: MutableList<CalendarEvent>,
        colorMap: Map<String, Int>
    ): SyncResult {
        val providerEvents = loadProviderEvents(calendarId, colorMap)
        var processedCount = 0
        var deletionsCount = 0
        val providerIds = providerEvents.keys.toSet()

        for (id in providerIds) {
            val pEvent = providerEvents[id]!!
            val fEvent = fileEventsMap[id]

            // Check for completion first
            if (pEvent.title.startsWith("[x] ", ignoreCase = true) || pEvent.title.startsWith("[X] ", ignoreCase = true)) {
                 if (fEvent != null) {
                     completeTask(fEvent)
                 }
                 deleteFromProvider(id)
                 deletionsCount++
                 continue
            }

            if (pEvent.deleted) {
                if (fEvent != null) {
                    // For tasks, if deleted from calendar, we just remove the reminder from the file, NOT delete the file
                    removeReminderFromFile(fEvent)
                    processedCount++
                }
                deleteFromProvider(id)
            } else if (pEvent.dirty) {
                // Update task file
                var updatedEvent = providerEventToModel(pEvent, fEvent?.fileName, "Tasks")
                
                // Enforce Task Rules: 10 min duration and "[] " prefix
                if (!updatedEvent.title.startsWith("[] ")) {
                    updatedEvent = updatedEvent.copy(title = "[] " + updatedEvent.title)
                }
                val properEnd = updatedEvent.start.plusMinutes(10)
                if (updatedEvent.end != properEnd) {
                    updatedEvent = updatedEvent.copy(end = properEnd)
                }

                saveTaskFile(updatedEvent, fEvent?.fileName) // Pass original filename
                
                // Always update provider to ensure formatting is reflected and DIRTY flag is cleared
                updateProviderEvent(updatedEvent, calendarId)
                
                processedCount++
            } else {
                if (fEvent == null) {
                    deleteFromProvider(id)
                    deletionsCount++
                } else {
                    // Check if we need to update provider (e.g. file changed)
                    // For now, assume provider is master for "dirty" checks, but if file changed externally we should update provider.
                    // Simplified: always update provider from file if not dirty
                     updateProviderEvent(fEvent, calendarId)
                     processedCount++
                }
            }
        }

        val orphanedIds = fileEventsMap.keys.filter { it !in providerIds }
        for (id in orphanedIds) {
             // These are files that have a system ID but aren't in the provider? 
             // This implies the provider was cleared or database lost. Re-insert them.
             // OR, they were deleted from provider and we missed the sync.
             // Safest is to treat them as new insertions if ID is invalid, but here we have the ID.
             // We'll treat them as deleted from provider -> remove reminder.
             val event = fileEventsMap[id]!!
             removeReminderFromFile(event)
             deletionsCount++
        }

        for (event in newFileEvents) {
            val newId = insertProviderEvent(event, calendarId)
            if (newId != null) {
                saveTaskFile(event.copy(systemEventId = newId), event.fileName)
                processedCount++
            }
        }

        return SyncResult(processedCount, deletionsCount)
    }

    private fun scanTaskFolder(folder: DocumentFile, relativePath: String, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) {
                if (file.name != ".Archive" && file.name != ".Deleted" && file.name != "Pinned") { // MarkIt specific folders to ignore? Or Pinned is valid?
                     // MarkIt keeps Pinned INSIDE folders usually. 
                     // But we should ignore .Archive and .Deleted
                     scanTaskFolder(file, if (relativePath.isEmpty()) file.name!! else "$relativePath/${file.name}", map, newList)
                } else if (file.name == "Pinned") {
                     scanTaskFolder(file, relativePath, map, newList) // Flatten Pinned into current path
                }
            } else if (file.name?.endsWith(".md") == true) {
                parseTaskFile(file, relativePath)?.let { event ->
                    if (event.systemEventId != null) {
                        map[event.systemEventId] = event
                    } else {
                        newList.add(event)
                    }
                }
            }
        }
    }

    private fun parseTaskFile(file: DocumentFile, relativePath: String): CalendarEvent? {
        try {
            val content = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: return null
            
            // 1. Split YAML and Body
            // Matches start of file --- ... --- or just content if no YAML
            val frontMatterRegex = Regex("""^---\s*\n([\s\S]*?)\n---\s*(?:\n(.*))?$""", RegexOption.MULTILINE)
            val matchResult = frontMatterRegex.find(content)

            val yamlContent = matchResult?.groups?.get(1)?.value
            val bodyContent = matchResult?.groups?.get(2)?.value?.trim() ?: (if (matchResult == null) content.trim() else "")

            if (yamlContent == null) return null // Must have YAML to be a valid Task with reminder? 
            // Actually, if no YAML, it can't have a reminder field, so return null.
            
            // 2. Parse Reminder from YAML content ONLY
            val reminderRegex = Regex("""^reminder:\s*(.*)$""", RegexOption.MULTILINE)
            val match = reminderRegex.find(yamlContent) ?: return null
            val reminderValue = match.groupValues[1].trim()

            val dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val start = try {
                java.time.LocalDateTime.parse(reminderValue, dateFormat)
            } catch (e: Exception) {
                try {
                    val millis = reminderValue.toLong()
                    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                } catch (e2: Exception) {
                    return null // Invalid reminder
                }
            }

            // 3. Extract System ID from YAML
            val idRegex = Regex("""^system_id:\s*(\d+)$""", RegexOption.MULTILINE)
            val idMatch = idRegex.find(yamlContent)
            val systemId = idMatch?.groupValues?.get(1)?.toLongOrNull()

            // 4. Extract Color
            val colorRegex = Regex("""^color:\s*(.*)$""", RegexOption.MULTILINE)
            val colorMatch = colorRegex.find(yamlContent)
            val colorValStr = colorMatch?.groupValues?.get(1)?.trim()
            val color = if (colorValStr != null) {
                try {
                    if (colorValStr.startsWith("#")) {
                        android.graphics.Color.parseColor(colorValStr)
                    } else {
                        colorValStr.toInt()
                    }
                } catch (e: Exception) {
                    null
                }
            } else null

            val title = file.name?.substringBeforeLast(".md") ?: "Untitled"
            
            return CalendarEvent(
                title = "[] $title",
                start = start,
                end = start.plusMinutes(10),
                isAllDay = false,
                calendarName = "Tasks",
                fileName = "$relativePath/${file.name}", // Store relative path to locate file later
                systemEventId = systemId,
                color = color,
                body = bodyContent // Clean body without YAML
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun saveTaskFile(event: CalendarEvent, originalFileName: String?) {
        var currentDir = taskRootFolder
        var fileName: String
        var file: DocumentFile? = null

        if (originalFileName != null) {
            // Existing File Logic
            val parts = originalFileName.split("/")
            var validPath = true
            for (i in 0 until parts.size - 1) {
                currentDir = currentDir?.findFile(parts[i])
                if (currentDir == null) {
                    SyncLogger.log(context, "Error: Could not find folder ${parts[i]} for file $originalFileName")
                    validPath = false
                    break
                }
            }
            
            if (validPath) {
                fileName = parts.last()
                file = currentDir?.findFile(fileName)
                
                if (file == null) {
                     SyncLogger.log(context, "Error: File $originalFileName not found. Skipping to avoid duplication.")
                     return
                }
            } else {
                return
            }
        } else {
            // New File Logic
            // Default to Inbox if exists, else Root
            val inbox = taskRootFolder?.findFile("Inbox")
            if (inbox != null && inbox.isDirectory) {
                currentDir = inbox
            }
            
            // Clean Title for Filename
            var cleanTitle = if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title
            cleanTitle = cleanTitle.replace("/", "-").replace(":", "-") // Basic sanitization
            if (cleanTitle.isBlank()) cleanTitle = "Untitled"
            
            fileName = "$cleanTitle.md"
            
            // Handle Collision (Auto-rename new file)
            var counter = 1
            var tempFileName = fileName
            while (currentDir?.findFile(tempFileName) != null) {
                tempFileName = "$cleanTitle ($counter).md"
                counter++
            }
            fileName = tempFileName
            
            file = currentDir?.createFile("text/markdown", fileName)
        }
        
        if (file == null) return

        try {
            // --- Renaming Logic (Only for existing files) ---
            if (originalFileName != null) {
                val cleanTitle = if (event.title.startsWith("[] ")) event.title.substring(3).trim() else event.title
                val desiredFileName = "$cleanTitle.md"
                
                // Only rename if name actually changed
                if (desiredFileName != fileName) {
                     val existingTarget = currentDir?.findFile(desiredFileName)
                     if (existingTarget == null) {
                         if (file.renameTo(desiredFileName)) {
                             fileName = desiredFileName
                             // Refresh file reference
                             file = currentDir?.findFile(fileName) ?: return
                         }
                     }
                }
            }

            var content = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: ""
            
            // Update Reminder in YAML
            val dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val newReminderStr = event.start.format(dateFormat)
            val colorStr = String.format("#%08X", (event.color ?: -1).toLong() and 0xFFFFFFFF)
            
            // We need to work on the YAML block specifically
            val frontMatterRegex = Regex("""^(---\s*\n)([\s\S]*?)(\n---)""", RegexOption.MULTILINE)
            val matchResult = frontMatterRegex.find(content)

            var finalContent = ""

            if (matchResult != null) {
                val headerStart = matchResult.groups[1]!!.value
                var yamlBody = matchResult.groups[2]!!.value
                val headerEnd = matchResult.groups[3]!!.value
                
                // Replace or Add Reminder
                val reminderRegex = Regex("""^reminder:.*$""", RegexOption.MULTILINE)
                if (reminderRegex.containsMatchIn(yamlBody)) {
                    yamlBody = reminderRegex.replace(yamlBody, "reminder: $newReminderStr")
                } else {
                    yamlBody += "\nreminder: $newReminderStr"
                }

                // Replace or Add Color
                val colorRegex = Regex("""^color:.*$""", RegexOption.MULTILINE)
                if (colorRegex.containsMatchIn(yamlBody)) {
                    yamlBody = colorRegex.replace(yamlBody, "color: $colorStr")
                } else {
                    yamlBody += "\ncolor: $colorStr"
                }

                // Replace or Add System ID
                if (event.systemEventId != null) {
                    val idRegex = Regex("""^system_id:.*$""", RegexOption.MULTILINE)
                    if (idRegex.containsMatchIn(yamlBody)) {
                        yamlBody = idRegex.replace(yamlBody, "system_id: ${event.systemEventId}")
                    } else {
                        yamlBody += "\nsystem_id: ${event.systemEventId}"
                    }
                }
                
                // Reconstruct content with NEW BODY
                val newHeader = "$headerStart$yamlBody$headerEnd"
                finalContent = "$newHeader\n\n${event.body}"
            } else {
                // No YAML header found? Create one.
                val newYaml = "---\n" +
                              "color: $colorStr\n" +
                              "reminder: $newReminderStr\n" + 
                              (if (event.systemEventId != null) "system_id: ${event.systemEventId}\n" else "") + 
                              "---\n\n"
                finalContent = newYaml + event.body
            }

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(finalContent.toByteArray())
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun completeTask(event: CalendarEvent) {
        val relativePath = event.fileName ?: return
        val parts = relativePath.split("/")
        var currentDir = taskRootFolder
        // Traverse to parent folder of the file
        for (i in 0 until parts.size - 1) {
            currentDir = currentDir?.findFile(parts[i]) ?: return
        }
        val fileName = parts.last()
        val sourceFile = currentDir?.findFile(fileName) ?: return

        try {
            // 1. Read and Modify Content (Remove reminder)
            var content = context.contentResolver.openInputStream(sourceFile.uri)?.use { it.bufferedReader().readText() } ?: return
            
            val reminderRegex = Regex("""^reminder:.*$\n?""", RegexOption.MULTILINE)
            content = reminderRegex.replace(content, "")
            
            // Remove system_id as well since it's leaving the calendar
            val idRegex = Regex("""^system_id:.*$\n?""", RegexOption.MULTILINE)
            content = idRegex.replace(content, "")

            // 2. Move to Archive
            // Create corresponding folder structure in .Archive
            val archiveRoot = getOrCreateFolder(taskRootFolder, ".Archive")
            var targetDir = archiveRoot
            for (i in 0 until parts.size - 1) {
                targetDir = getOrCreateFolder(targetDir, parts[i])
            }
            
            val targetFile = targetDir?.createFile("text/markdown", fileName)
            
            if (targetFile != null) {
                context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { output ->
                    output.write(content.toByteArray())
                }
                sourceFile.delete()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeReminderFromFile(event: CalendarEvent) {
         val relativePath = event.fileName ?: return
        val parts = relativePath.split("/")
        var currentDir = taskRootFolder
        for (i in 0 until parts.size - 1) {
            currentDir = currentDir?.findFile(parts[i]) ?: return
        }
        val fileName = parts.last()
        val file = currentDir?.findFile(fileName) ?: return

        try {
            var content = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: return
            
            val reminderRegex = Regex("""^reminder:.*$\n?""", RegexOption.MULTILINE)
            content = reminderRegex.replace(content, "")
            
            val idRegex = Regex("""^system_id:.*$\n?""", RegexOption.MULTILINE)
            content = idRegex.replace(content, "")

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(content.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun syncCalendar(
        calendarName: String, 
        calendarId: Long, 
        folder: DocumentFile, 
        fileEventsMap: MutableMap<Long, CalendarEvent>,
        newFileEvents: MutableList<CalendarEvent>,
        colorMap: Map<String, Int>
    ): SyncResult {
        // fileEventsMap and newFileEvents are already populated
        
        val providerEvents = loadProviderEvents(calendarId, colorMap)
        
        var processedCount = 0
        var deletionsCount = 0
        val providerIds = providerEvents.keys.toSet()
        
        for (id in providerIds) {
            val pEvent = providerEvents[id]!!
            val fEvent = fileEventsMap[id]

            if (pEvent.deleted) {
                if (fEvent != null) {
                    deleteFile(fEvent)
                    deletionsCount++
                }
                deleteFromProvider(id)
            } 
            else if (pEvent.dirty) {
                val updatedEvent = providerEventToModel(pEvent, fEvent?.fileName, calendarName)
                saveToFile(updatedEvent)
                clearDirtyFlag(id)
                processedCount++
            }
            else {
                if (fEvent == null) {
                    deleteFromProvider(id)
                    deletionsCount++
                } else {
                    updateProviderEvent(fEvent, calendarId)
                    processedCount++
                }
            }
        }

        val orphanedIds = fileEventsMap.keys.filter { it !in providerIds }
        for (id in orphanedIds) {
            deleteFile(fileEventsMap[id]!!)
            deletionsCount++
        }

        for (event in newFileEvents) {
            val newId = insertProviderEvent(event, calendarId)
            if (newId != null) {
                saveToFile(event.copy(systemEventId = newId))
                processedCount++
            }
        }
        
        return SyncResult(processedCount, deletionsCount)
    }

    private fun getProviderUsedColors(): Set<Int> {
        val usedColors = mutableSetOf<Int>()
        
        // 1. Get explicit colors
        val projection = arrayOf(CalendarContract.Events.EVENT_COLOR, CalendarContract.Events.EVENT_COLOR_KEY)
        val selection = "${CalendarContract.Events.ACCOUNT_NAME} = ? AND ${CalendarContract.Events.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(accountName, accountType)
        
        val usedKeys = mutableSetOf<String>()

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val colorCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_COLOR)
            val keyCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_COLOR_KEY)
            
            while (cursor.moveToNext()) {
                if (!cursor.isNull(colorCol)) {
                    usedColors.add(cursor.getInt(colorCol))
                }
                if (!cursor.isNull(keyCol)) {
                    usedKeys.add(cursor.getString(keyCol))
                }
            }
        }

        // 2. Resolve keys to colors
        if (usedKeys.isNotEmpty()) {
            val uri = asSyncAdapter(CalendarContract.Colors.CONTENT_URI)
            val projectionColors = arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR)
            context.contentResolver.query(
                uri,
                projectionColors,
                "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?",
                arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()),
                null
            )?.use { cursor ->
                val keyCol = cursor.getColumnIndex(CalendarContract.Colors.COLOR_KEY)
                val valCol = cursor.getColumnIndex(CalendarContract.Colors.COLOR)
                while (cursor.moveToNext()) {
                    val key = cursor.getString(keyCol)
                    if (key in usedKeys) {
                        usedColors.add(cursor.getInt(valCol))
                    }
                }
            }
        }
        
        return usedColors
    }

    private fun manageColors(usedColors: Set<Int>): Map<String, Int> {
        val validColorsMap = defaultColors.toMutableMap()
        val uri = asSyncAdapter(CalendarContract.Colors.CONTENT_URI)
        
        // 1. Fetch existing colors
        val existingColors = mutableMapOf<String, Int>()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR),
            "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_TYPE} = ?",
            arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                existingColors[cursor.getString(0)] = cursor.getInt(1)
            }
        }

        // 2. Identify deletions (Custom colors that are NOT used)
        for ((key, color) in existingColors) {
            val isDefault = defaultColors.containsKey(key)
            val isUsed = color in usedColors
            
            if (!isDefault && !isUsed) {
                // Delete unused custom color
                context.contentResolver.delete(
                    uri,
                    "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND ${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND ${CalendarContract.Colors.COLOR_KEY} = ?",
                    arrayOf(accountName, accountType, key)
                )
            } else {
                validColorsMap[key] = color
            }
        }

        // 3. Identify additions (Default missing OR Used custom missing)
        
        // Ensure defaults
        for ((key, color) in defaultColors) {
            if (key !in existingColors) {
                insertColor(key, color)
                validColorsMap[key] = color
            }
        }
        
        // Ensure used custom colors
        for (color in usedColors) {
            // Check if this color already exists in valid map (either as default or existing custom)
            if (!validColorsMap.containsValue(color)) {
                // Insert new custom color
                val newKey = "custom_$color"
                insertColor(newKey, color)
                validColorsMap[newKey] = color
            }
        }
        
        return validColorsMap
    }

    private fun insertColor(key: String, color: Int) {
        val uri = asSyncAdapter(CalendarContract.Colors.CONTENT_URI)
        val values = ContentValues().apply {
            put(CalendarContract.Colors.ACCOUNT_NAME, accountName)
            put(CalendarContract.Colors.ACCOUNT_TYPE, accountType)
            put(CalendarContract.Colors.COLOR_KEY, key)
            put(CalendarContract.Colors.COLOR, color)
            put(CalendarContract.Colors.COLOR_TYPE, CalendarContract.Colors.TYPE_EVENT)
        }
        context.contentResolver.insert(uri, values)
    }

    private fun loadOurCalendars(): Map<String, Long> {
        val calendars = mutableMapOf<String, Long>()
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(accountName, accountType)
        
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                calendars[name] = id
            }
        }
        return calendars
    }

    private fun scanFolder(calendarName: String, folder: DocumentFile, map: MutableMap<Long, CalendarEvent>, newList: MutableList<CalendarEvent>) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) {
                if (file.name != "_Recurring") {
                    scanFolder(calendarName, file, map, newList)
                } else {
                    scanFolder(calendarName, file, map, newList)
                }
            } else if (file.name?.endsWith(".md") == true) {
                parseFile(file, calendarName)?.let { event ->
                    if (event.systemEventId != null) {
                        map[event.systemEventId] = event
                    } else {
                        newList.add(event)
                    }
                }
            }
        }
    }

    private fun parseFile(file: DocumentFile, calendarName: String): CalendarEvent? {
        return try {
            val content = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
            content?.let { YamlConverter.parseMarkdown(it, file.name ?: "", calendarName) }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadProviderEvents(calendarId: Long, colorMap: Map<String, Int>): Map<Long, CalendarProviderEvent> {
        val events = mutableMapOf<Long, CalendarProviderEvent>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.EVENT_COLOR_KEY
        )
        
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ?"
        val selectionArgs = arrayOf(calendarId.toString())

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleCol = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val dtStartCol = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val dtEndCol = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val descCol = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val dirtyCol = cursor.getColumnIndex(CalendarContract.Events.DIRTY)
            val delCol = cursor.getColumnIndex(CalendarContract.Events.DELETED)
            val rruleCol = cursor.getColumnIndex(CalendarContract.Events.RRULE)
            val allDayCol = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val locationCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val tzCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)
            val colorCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_COLOR)
            val colorKeyCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_COLOR_KEY)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                
                val colorInt = if (cursor.isNull(colorCol)) null else cursor.getInt(colorCol)
                val colorKey = if (cursor.isNull(colorKeyCol)) null else cursor.getString(colorKeyCol)
                val finalColor = colorInt ?: if (colorKey != null) colorMap[colorKey] else null

                events[id] = CalendarProviderEvent(
                    id = id,
                    title = cursor.getString(titleCol) ?: "",
                    dtStart = cursor.getLong(dtStartCol),
                    dtEnd = cursor.getLong(dtEndCol),
                    description = cursor.getString(descCol) ?: "",
                    dirty = cursor.getInt(dirtyCol) == 1,
                    deleted = cursor.getInt(delCol) == 1,
                    recurrenceRule = cursor.getString(rruleCol),
                    allDay = cursor.getInt(allDayCol) == 1,
                    location = cursor.getString(locationCol),
                    timezone = cursor.getString(tzCol),
                    color = finalColor,
                    attendees = loadAttendees(id),
                    reminders = loadReminders(id)
                )
            }
        }
        return events
    }

    private fun loadAttendees(eventId: Long): List<String> {
        val attendees = mutableListOf<String>()
        val projection = arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL)
        val selection = "${CalendarContract.Attendees.EVENT_ID} = ?"
        val selectionArgs = arrayOf(eventId.toString())

        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { attendees.add(it) }
            }
        }
        return attendees
    }

    private fun loadReminders(eventId: Long): List<Int> {
        val reminders = mutableListOf<Int>()
        val projection = arrayOf(CalendarContract.Reminders.MINUTES)
        val selection = "${CalendarContract.Reminders.EVENT_ID} = ?"
        val selectionArgs = arrayOf(eventId.toString())

        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                reminders.add(cursor.getInt(0))
            }
        }
        return reminders
    }

    private fun deleteFromProvider(id: Long) {
        val uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
            .buildUpon().appendPath(id.toString()).build()
        context.contentResolver.delete(uri, null, null)
    }

    private fun clearDirtyFlag(id: Long) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.DIRTY, 0)
        }
        val uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
            .buildUpon().appendPath(id.toString()).build()
        context.contentResolver.update(uri, values, null, null)
    }

    private fun insertProviderEvent(event: CalendarEvent, calendarId: Long): Long? {
        val values = eventToContentValues(event, calendarId)
        val uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
        val eventUri = context.contentResolver.insert(uri, values)
        val eventId = eventUri?.lastPathSegment?.toLongOrNull()
        
        if (eventId != null) {
            syncAttendees(eventId, event.attendees)
            syncReminders(eventId, event.reminders)
        }
        return eventId
    }

    private fun updateProviderEvent(event: CalendarEvent, calendarId: Long) {
        val eventId = event.systemEventId ?: return
        val values = eventToContentValues(event, calendarId)
        values.put(CalendarContract.Events.DIRTY, 0)
        val uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
            .buildUpon().appendPath(eventId.toString()).build()
        context.contentResolver.update(uri, values, null, null)
        
        syncAttendees(eventId, event.attendees)
        syncReminders(eventId, event.reminders)
    }

    private fun syncAttendees(eventId: Long, attendees: List<String>) {
        val uri = asSyncAdapter(CalendarContract.Attendees.CONTENT_URI)
        context.contentResolver.delete(uri, "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()))
        attendees.forEach { email ->
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
            }
            context.contentResolver.insert(uri, values)
        }
    }

    private fun syncReminders(eventId: Long, reminders: List<Int>) {
        val uri = asSyncAdapter(CalendarContract.Reminders.CONTENT_URI)
        context.contentResolver.delete(uri, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        reminders.forEach { minutes ->
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(uri, values)
        }
    }

    private fun eventToContentValues(event: CalendarEvent, calendarId: Long): ContentValues {
        return ContentValues().apply {
            val zoneId = event.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
            put(CalendarContract.Events.DTSTART, event.start.atZone(zoneId).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, event.end.atZone(zoneId).toInstant().toEpochMilli())
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.body)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, zoneId.id)
            put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
            if (event.color != null) put(CalendarContract.Events.EVENT_COLOR, event.color)
            if (event.location != null) put(CalendarContract.Events.EVENT_LOCATION, event.location)
            if (event.recurrenceRule != null) {
                put(CalendarContract.Events.RRULE, event.recurrenceRule)
            }
        }
    }

    private fun providerEventToModel(pEvent: CalendarProviderEvent, existingFileName: String?, calendarName: String): CalendarEvent {
        val zoneId = pEvent.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(pEvent.dtStart).atZone(zoneId).toLocalDateTime()
        val end = if (pEvent.dtEnd > 0) {
            Instant.ofEpochMilli(pEvent.dtEnd).atZone(zoneId).toLocalDateTime()
        } else {
            start.plusHours(1)
        }

        return CalendarEvent(
            title = pEvent.title,
            start = start,
            end = end,
            isAllDay = pEvent.allDay,
            location = pEvent.location,
            attendees = pEvent.attendees,
            reminders = pEvent.reminders,
            timezone = pEvent.timezone,
            color = pEvent.color,
            body = pEvent.description,
            systemEventId = pEvent.id,
            recurrenceRule = pEvent.recurrenceRule,
            calendarName = calendarName,
            fileName = existingFileName
        )
    }

    private fun saveToFile(event: CalendarEvent) {
        val calendarFolder = getOrCreateFolder(rootFolder, event.calendarName)
        
        val targetFolder = if (event.recurrenceRule != null) {
            getOrCreateFolder(calendarFolder, "_Recurring")
        } else {
            val yearFolder = getOrCreateFolder(calendarFolder, event.start.year.toString())
            getOrCreateFolder(yearFolder, String.format("%02d", event.start.monthValue))
        }

        val sanitizedTitle = sanitizeFilename(event.title)
        val expectedFileName = if (event.recurrenceRule != null) {
            "$sanitizedTitle.md"
        } else {
            "${event.start.toLocalDate()}_$sanitizedTitle.md"
        }

        // Rename Logic
        if (event.fileName != null && event.fileName != expectedFileName) {
            val oldFile = targetFolder?.findFile(event.fileName)
            if (oldFile != null) {
                // Ensure target doesn't exist to avoid overwrite/collision
                if (targetFolder?.findFile(expectedFileName) == null) {
                    if (oldFile.renameTo(expectedFileName)) {
                        // Success
                    }
                }
            }
        }

        var file = targetFolder?.findFile(expectedFileName)
        if (file == null) {
            file = targetFolder?.createFile("text/markdown", expectedFileName)
        }

        file?.let {
            context.contentResolver.openOutputStream(it.uri, "wt")?.use { output ->
                output.write(YamlConverter.toMarkdown(event).toByteArray())
            }
        }
    }

    private fun deleteFile(event: CalendarEvent) {
        val calendarFolder = rootFolder?.findFile(event.calendarName)
        val targetFolder = if (event.recurrenceRule != null) {
             calendarFolder?.findFile("_Recurring")
        } else {
             calendarFolder?.findFile(event.start.year.toString())
                ?.findFile(String.format("%02d", event.start.monthValue))
        }
        
        val sourceFile = targetFolder?.findFile(event.fileName ?: "") ?: return

        val deletedFolder = getOrCreateFolder(rootFolder, ".Deleted")
        val deletedFileName = "${event.calendarName}_${System.currentTimeMillis()}_${event.fileName}"
        
        val targetFile = deletedFolder?.createFile("text/markdown", deletedFileName)
        if (targetFile != null) {
            try {
                context.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                    context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                sourceFile.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getOrCreateCalendarId(name: String): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.NAME} = ?"
        val selectionArgs = arrayOf(accountName, name)
        
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "App: $name")
            put(CalendarContract.Calendars.CALENDAR_COLOR, android.graphics.Color.BLUE)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }

        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        return context.contentResolver.insert(uri, values)?.lastPathSegment?.toLongOrNull()
    }

    private fun asSyncAdapter(uri: Uri): Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }

    private fun getOrCreateFolder(parent: DocumentFile?, name: String): DocumentFile? {
        return parent?.findFile(name) ?: parent?.createDirectory(name)
    }

    private fun deleteCalendar(name: String) {
        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        context.contentResolver.delete(
            uri,
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?",
            arrayOf(accountName, accountType, name)
        )
    }

    private fun sanitizeFilename(name: String): String {
        val reserved = Regex("[<>:\"/\\\\|?*]")
        var sanitized = reserved.replace(name, "-")
        sanitized = sanitized.trim().trimEnd('.')
        if (sanitized.isEmpty()) sanitized = "Untitled"
        return sanitized
    }
}

data class CalendarProviderEvent(
    val id: Long,
    val title: String,
    val dtStart: Long,
    val dtEnd: Long,
    val description: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val recurrenceRule: String?,
    val allDay: Boolean,
    val location: String?,
    val timezone: String?,
    val color: Int?,
    val attendees: List<String> = emptyList(),
    val reminders: List<Int> = emptyList()
)