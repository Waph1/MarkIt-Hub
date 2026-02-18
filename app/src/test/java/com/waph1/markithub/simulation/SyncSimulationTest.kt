package com.waph1.markithub.simulation

import com.waph1.markithub.util.YamlConverter
import com.waph1.markithub.model.CalendarEvent
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SyncSimulationTest {

    private fun sanitize(name: String): String {
        return name.replace(Regex("[<>:\"/\\\\|?*]"), "-").trim()
    }

    @Test
    fun runSimulation() {
        val root = File("Mock tests")
        if (!root.exists()) return
        
        // 1. Case-Insensitive Folder Merge
        val folders = root.listFiles { f -> f.isDirectory } ?: emptyArray()
        folders.groupBy { it.name.lowercase() }.forEach { (lowName, group) ->
            if (group.size > 1) {
                val oldest = group.minByOrNull { it.lastModified() }!!
                group.forEach { if (it != oldest) {
                    it.listFiles()?.forEach { file -> 
                        val target = File(oldest, file.name)
                        file.renameTo(target)
                    }
                    it.delete()
                }}
            }
        }

        // 2. Scan and Process Files
        root.walkTopDown().filter { it.isFile && it.extension == "md" }
            .sortedBy { it.absolutePath.length }
            .toList().forEach { file ->
            val originalName = file.name
            val content = file.readText()
            val event = YamlConverter.parseMarkdown(content, originalName, "Default")
            
            val fileNameWithoutExt = originalName.substringBeforeLast(".md")
            val datePart = fileNameWithoutExt.substringBefore("_", "")
            val titlePart = if (fileNameWithoutExt.contains("_")) fileNameWithoutExt.substringAfter("_") else fileNameWithoutExt
            
            val parsedDate = try { LocalDate.parse(datePart) } catch (e: Exception) { null }
            
            var finalTitle = titlePart.replace("_", " ")
            var finalDate = parsedDate ?: event.start?.toLocalDate() ?: LocalDate.now()
            var finalTime = event.start?.toLocalTime() ?: LocalDateTime.of(2026,1,1,0,0).toLocalTime()
            var finalIsAllDay = event.isAllDay || (parsedDate != null && !content.contains("reminder:"))

            val eventStart = event.start
            if (parsedDate == null && eventStart != null) {
                finalDate = eventStart.toLocalDate()
                finalTime = eventStart.toLocalTime()
            }

            val baseName = "${finalDate}_${sanitize(finalTitle)}"
            var expectedName = "$baseName.md"
            val targetDir = File(root, "Personal/${finalDate.year}/${String.format("%02d", finalDate.monthValue)}")
            targetDir.mkdirs()
            
            var targetFile = File(targetDir, expectedName)
            if (targetFile.exists() && targetFile.absolutePath != file.absolutePath) {
                var counter = 1
                while (File(targetDir, "${finalDate}_${sanitize(finalTitle)} ($counter).md").exists()) counter++
                expectedName = "${finalDate}_${sanitize(finalTitle)} ($counter).md"
                targetFile = File(targetDir, expectedName)
            }

            val updatedEvent = event.copy(title = finalTitle, start = finalDate.atTime(finalTime), isAllDay = finalIsAllDay)
            file.writeText(YamlConverter.toMarkdown(updatedEvent))
            if (file.absolutePath != targetFile.absolutePath) file.renameTo(targetFile)
        }

        // VERIFICATIONS
        println("--- VERIFYING RESULTS ---")
        
        // Check Folder Merge
        assertFalse("Folder 'work' should have been merged into 'Work'", File(root, "work").exists())
        assertTrue("Folder 'Work' should contain Task2.md", File(root, "Work/Task2.md").exists())

        // Check All-Day logic
        val holidayFile = File(root, "Personal/2026/02/2026-02-22_Holiday.md")
        assertTrue("Holiday file should exist", holidayFile.exists())
        assertTrue("Holiday should be All-Day", holidayFile.readText().contains("all_day: true"))

        // Check Conflict Logic
        val dentist1 = File(root, "Personal/2026/02/2026-02-20_Dentist.md")
        val dentist2 = File(root, "Personal/2026/02/2026-02-20_Dentist (1).md")
        assertTrue("Both dentist files should exist", dentist1.exists() && dentist2.exists())

        // Check Smart No-YAML extraction
        val apptFile = File(root, "Personal/2026/02/2026-02-21_Appointment.md")
        assertTrue("Appointment should have been renamed with date", apptFile.exists())
        assertTrue("Appointment body should be cleaned", apptFile.readText().contains("This is the actual appointment text."))
        assertFalse("Metadata line should be removed from body", apptFile.readText().contains("reminder: \"2026-02-21 15:00\""))

        println("--- ALL SIMULATION TESTS PASSED ---")
    }
}
