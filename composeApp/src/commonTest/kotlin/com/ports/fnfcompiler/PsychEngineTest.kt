package com.ports.fnfcompiler

import com.ports.fnfcompiler.core.Analyzer
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.RepoRef
import com.ports.fnfcompiler.core.SourceFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
