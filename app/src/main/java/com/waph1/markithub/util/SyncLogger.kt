package com.waph1.markithub.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SyncLogger {
    private const val LOG_FILE_NAME = "sync_history.txt"
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024 // 10MB

    @Synchronized
    fun log(context: Context, message: String) {
        val file = File(context.filesDir, LOG_FILE_NAME)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val logEntry = "[$timestamp] $message\n"

        try {
            // Check file size and rotate/trim if necessary (simple implementation: delete if too big)
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                file.writeText("Log rotated due to size limit.\n")
            }
            file.appendText(logEntry)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context): List<String> {
        val file = File(context.filesDir, LOG_FILE_NAME)
        return if (file.exists()) {
            try {
                file.readLines().reversed() // Show newest first
            } catch (e: IOException) {
                listOf("Error reading logs: ${e.message}")
            }
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    fun export(context: Context) {
        val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
        val rootUriString = prefs.getString("rootUri", null) ?: return
        val root = android.net.Uri.parse(rootUriString)
        
        try {
            val logs = getLogs(context).joinToString("\n")
            val resolver = context.contentResolver
            
            val rootId = android.provider.DocumentsContract.getTreeDocumentId(root)
            val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, rootId)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(root, rootId)
            
            var existingLogUri: android.net.Uri? = null
            resolver.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "Sync.log") {
                        existingLogUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                        break
                    }
                }
            }

            val targetUri = existingLogUri ?: android.provider.DocumentsContract.createDocument(resolver, rootDocUri, "text/plain", "Sync.log")
            targetUri?.let { uri ->
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(logs.toByteArray())
                }
                log(context, "Logs exported successfully to Sync.log")
            }
        } catch (e: Exception) {
            log(context, "Export failed: ${e.message}")
        }
    }
}
