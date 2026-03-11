package com.waph1.markithub.simulation

import com.waph1.markithub.util.YamlConverter
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class YamlTest {
    @Test
    fun testYaml() {
        val content = """---
color: "#ffffffff"
reminder: "2026-03-05 12:00"
---

Hello world
Some long body here
And more
"""
        val event = YamlConverter.parseMarkdown(content, "Test.md", "Tasks")
        val withId = event.copy(systemEventId = 12345L)
        val out = YamlConverter.toMarkdown(withId)
        println("OUTPUT:")
        println(out)
    }
}
