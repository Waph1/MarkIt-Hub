package com.waph1.markithub.util

import android.content.Context
import android.provider.DocumentsContract
import android.net.Uri
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SyncLogger {
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB per log

    @Synchronized
    fun log(context: Context, logName: String, message: String) {
        val fileName = "${logName}_history.txt"
        val file = File(context.filesDir, fileName)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val logEntry = "[$timestamp] $message\n"

        try {
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                file.writeText("Log rotated due to size limit.\n")
            }
            file.appendText(logEntry)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context, logName: String): List<String> {
        val fileName = "${logName}_history.txt"
        val file = File(context.filesDir, fileName)
        return if (file.exists()) {
            try {
                file.readLines().reversed()
            } catch (e: IOException) {
                listOf("Error reading logs: ${e.message}")
            }
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun clearLogs(context: Context, logName: String) {
        val fileName = "${logName}_history.txt"
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }

    fun export(context: Context, logName: String, rootUri: Uri) {
        try {
            val logs = getLogs(context, logName).joinToString("\n")
            val resolver = context.contentResolver
            val exportFileName = "${logName.capitalize()}Sync.log"
            
            val rootId = DocumentsContract.getTreeDocumentId(rootUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, rootId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, rootId)
            
            var existingLogUri: Uri? = null
            resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == exportFileName) {
                        existingLogUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, cursor.getString(0))
                        break
                    }
                }
            }

            val targetUri = existingLogUri ?: DocumentsContract.createDocument(resolver, rootDocUri, "text/plain", exportFileName)
            targetUri?.let { uri ->
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(logs.toByteArray())
                }
                log(context, logName, "Logs exported successfully to $exportFileName")
            }
        } catch (e: Exception) {
            log(context, logName, "Export failed: ${e.message}")
        }
    }
}

private fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
