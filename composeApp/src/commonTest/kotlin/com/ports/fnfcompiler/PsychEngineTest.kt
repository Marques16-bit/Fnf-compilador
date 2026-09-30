package com.ports.fnfcompiler

import com.ports.fnfcompiler.core.Analyzer
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.RepoRef
import com.ports.fnfcompiler.core.SourceFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import com.ports.fnfcompiler.core.ArchiveException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapSource(private val files: Map<String, String>) : SourceFiles {
    override val names: List<String> = files.keys.toList()
    override val scanCap: Int = 1500
    override fun size(name: String): Long = files[name]?.length?.toLong() ?: 0L
    override suspend fun text(name: String): String = files.getValue(name)
}

class PsychEngineTest {
    private val projectXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <project>
            <app title="Friday Night Funkin': Psych Engine" file="PsychEngine" main="Main" />
            <define name="MODS_ALLOWED" if="desktop" />
            <define name="LUA_ALLOWED" if="desktop" />
            <define name="DISCORD_ALLOWED" />
            <haxelib name="flixel" version="5.6.1"/>
        </project>
    """.trimIndent()

    private val psych = MapSource(
        mapOf(
            "Project.xml" to projectXml,
            "setup/unix.sh" to "haxelib install flixel 5.6.1\nhaxelib install lime 8.1.2\n",
            "setup/windows.bat" to "haxelib install lime 8.1.2\n",
            "source/Main.hx" to "class Main { function new() { Sys.exit(1); } }",
            "assets/shared/images/icon.png" to ""
        )
    )

    @Test
    fun detectsPsychEngineWithSetupScript() = runTest {
        val result = Analyzer.analyze(psych, BuildTarget.WINDOWS)
        assertEquals("Psych Engine", result.engine.label)
        assertEquals("Setup script", result.libs.label)
        assertEquals("4.3.4", result.profile.haxe)
        assertEquals(listOf("officialBuild"), result.profile.defines)
        assertTrue(result.profile.modern)
        assertTrue(result.code.ok)
    }

    @Test
    fun warnsAboutDesktopOnlyFeaturesOnMobile() = runTest {
        val result = Analyzer.analyze(psych, BuildTarget.ANDROID)
        assertTrue(result.warnings.any { it.contains("Lua") })
        assertTrue(result.warnings.any { it.contains("touch controls") })
        assertTrue(result.warnings.any { it.contains("Sys.exit") })
    }

    private val vslice = MapSource(
        mapOf(
            "project.hxp" to "class Project extends HXProject {}",
            "hmm.json" to "{\"dependencies\":[{\"name\":\"lime\",\"type\":\"git\",\"ref\":\"abc\",\"url\":\"https://example.com\"}]}",
            ".gitmodules" to "[submodule \"assets\"]",
            "source/funkin/Main.hx" to "class Main {}"
        )
    )

    @Test
    fun detectsVSliceFromProjectHxp() = runTest {
        val result = Analyzer.analyze(vslice, BuildTarget.WINDOWS)
        assertTrue(result.hasProject)
        assertEquals("V-Slice (Funkin)", result.engine.label)
        assertEquals("project.hxp", result.project.label)
        assertEquals("vslice", result.profile.recipe)
        assertEquals("4.3.7", result.profile.haxe)
        assertEquals(listOf("GITHUB_BUILD"), result.profile.defines)
    }

    @Test
    fun vSliceMobileDisablesAdsAndPurchases() = runTest {
        val result = Analyzer.analyze(vslice, BuildTarget.ANDROID)
        assertTrue(result.profile.defines.contains("NO_FEATURE_MOBILE_ADVERTISEMENTS"))
        assertTrue(result.profile.defines.contains("NO_FEATURE_MOBILE_IAP"))
        assertTrue(result.warnings.any { it.contains("ASTC") })
        assertTrue(result.warnings.none { it.contains("touch controls") })
    }

    private val mario = MapSource(
        mapOf(
            "Project.xml" to """<project><app title="Friday Night Funkin': Mario's Madness" /><define name="LUA_ALLOWED" if="windows" /><haxelib name="flixel" /><haxelib name="hscript" /></project>""",
            "complations_preset.bat" to "haxelib set flixel 5.3.1\nhaxelib set lime 8.0.2\n",
            "source/Transparency.hx" to "@:headerCode(\"#include <windows.h>\") class Transparency {}",
            "source/backend/Native.hx" to "#if windows @:headerCode(\"#include <windows.h>\") #end class Native {}"
        )
    )

    @Test
    fun detectsMarioMadnessWithLegacyToolchain() = runTest {
        val result = Analyzer.analyze(mario, BuildTarget.WINDOWS)
        assertEquals("Mario's Madness", result.engine.label)
        assertEquals("Setup script", result.libs.label)
        assertEquals("4.2.5", result.profile.haxe)
        assertTrue(!result.profile.modern)
        assertTrue(result.profile.defines.isEmpty())
        assertTrue(result.warnings.none { it.contains("Windows API") })
        assertTrue(result.warnings.none { it.contains("only on Windows") })
    }

    @Test
    fun warnsWhenWindowsOnlySourceTargetsAnotherPlatform() = runTest {
        val result = Analyzer.analyze(mario, BuildTarget.LINUX)
        val warning = result.warnings.firstOrNull { it.contains("Windows API") }
        assertTrue(warning != null)
        assertTrue(warning.contains("Transparency.hx"))
        assertTrue(!warning.contains("Native.hx"))
        assertTrue(result.warnings.any { it.contains("only on Windows") })
        assertTrue(!result.code.ok)
    }

    @Test
    fun doesNotFlagFeaturesThatSeveralPlatformsShare() = runTest {
        val shared = MapSource(
            mapOf("Project.xml" to """<project><define name="VIDEOS_ALLOWED" if="windows || linux || android || mac" unless="32bits"/></project>""")
        )
        assertTrue(Analyzer.analyze(shared, BuildTarget.LINUX).warnings.none { it.contains("only on Windows") })
    }

    @Test
    fun acceptsDotsInFileNamesButRejectsTraversal() = runTest {
        val dotted = MapSource(mapOf("Project.xml" to "<project></project>", "assets/songs/Song...ogg" to ""))
        assertTrue(Analyzer.analyze(dotted, BuildTarget.WINDOWS).hasProject)
        val hostile = MapSource(mapOf("Project.xml" to "<project></project>", "../evil.txt" to ""))
        assertFailsWith<ArchiveException> { Analyzer.analyze(hostile, BuildTarget.WINDOWS) }
    }

    @Test
    fun parsesRepositoryReferences() {
        val plain = RepoRef.parse("https://github.com/ShadowMario/FNF-PsychEngine")
        assertEquals("ShadowMario/FNF-PsychEngine", plain?.slug)
        assertNull(plain?.ref)
        assertEquals("ShadowMario/FNF-PsychEngine", RepoRef.parse("https://github.com/ShadowMario/FNF-PsychEngine.git")?.slug)
        assertEquals("ShadowMario/FNF-PsychEngine", RepoRef.parse("ShadowMario/FNF-PsychEngine")?.slug)
        assertEquals("experimental", RepoRef.parse("https://github.com/ShadowMario/FNF-PsychEngine/tree/experimental")?.ref)
        assertNull(RepoRef.parse("not a url"))
    }
}
