package com.ports.fnfcompiler

import com.ports.fnfcompiler.core.Analyzer
import com.ports.fnfcompiler.core.ArchiveException
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.ZipArchive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class ModArchiveTest {
    private val fixture = Base64.decode(
        "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" +
        "c2V0cy9QSwUGAAAAAAUABQArAQAAIAQAAAAA"
    )

    @Test
    fun readsDeflatedAndStoredEntries() {
        val zip = ZipArchive(fixture)
        val names = zip.entries.map { it.name }
        assertTrue("mod/Project.xml" in names)
        assertEquals("stored entry", zip.readText(zip.entries.first { it.name == "mod/notes.txt" }))
        val main = zip.readText(zip.entries.first { it.name == "mod/source/Main.hx" })
        assertTrue(main.startsWith("class Main"))
        assertEquals(zip.entries.first { it.name == "mod/source/Main.hx" }.size.toInt(), main.encodeToByteArray().size)
    }

    @Test
    fun rejectsInvalidArchive() {
        assertFailsWith<ArchiveException> { ZipArchive(ByteArray(64)) }
    }

    @Test
    fun analyzesMobileTarget() {
        val result = Analyzer.analyze(fixture, BuildTarget.ANDROID)
        assertTrue(result.hasProject)
        assertEquals("Psych Engine", result.engine.label)
        assertFalse(result.libs.ok)
        assertFalse(result.code.ok)
        assertTrue(result.warnings.any { it.contains("flixel") })
        assertTrue(result.warnings.any { it.contains("Sys.exit") })
        assertEquals("Auto patch", result.project.label)
    }

    @Test
    fun ignoresMobileRulesOnDesktop() {
        val result = Analyzer.analyze(fixture, BuildTarget.WINDOWS)
        assertTrue(result.code.ok)
        assertEquals("No change", result.project.label)
    }
}
