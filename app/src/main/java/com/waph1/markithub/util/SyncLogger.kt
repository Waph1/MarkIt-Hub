package com.waph1.markithub.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SyncLogger {
    private const val LOG_FILE_NAME = "sync_history.txt"
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1MB

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
}
