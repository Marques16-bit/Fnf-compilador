package com.ports.fnfcompiler.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Finding(val ok: Boolean, val label: String)

data class Analysis(
    val engine: Finding,
    val libs: Finding,
    val code: Finding,
    val project: Finding,
    val warnings: List<String>,
    val scanned: Int,
    val hasProject: Boolean,
    val profile: BuildProfile
)

object Analyzer {
    private val ENGINES = listOf("forever" to "Forever Engine", "psych" to "Psych Engine", "kade" to "Kade Engine")

    private val MOBILE_RULES = listOf(
        Regex("discord_rpc|Discord\\.") to "Discord RPC does not exist on mobile. Wrap it in #if desktop.",
        Regex("Sys\\.command|Sys\\.exit") to "Sys.command and Sys.exit can break on mobile.",
        Regex("sys\\.thread|Thread\\.create") to "Threads can crash on mobile. Test them.",
        Regex("FileDialog|systools|hxvlc|VideoHandler") to "Video or native dialogs need a mobile compatible library.",
        Regex("#if\\s+(windows|desktop)") to "Desktop only block. Make sure a mobile fallback exists."
    )

    private val DESKTOP_ONLY_DEFINES = Regex("<define\\s+name=\"(MODS_ALLOWED|LUA_ALLOWED|HSCRIPT_ALLOWED)\"[^>]*if=\"desktop\"")
    private val TOUCH_MARKERS = listOf("mobilecontrols", "touchpad", "touchcontrols", "hitbox")

    private const val MAX_SCAN_BYTES = 500_000L
    private const val MAX_WARNINGS = 60
    private const val MODERN_HAXE = "4.3.4"

    suspend fun analyze(source: SourceFiles, target: BuildTarget): Analysis {
        val files = source.names
        if (files.any { it.contains("..") || it.startsWith("/") }) {
            throw ArchiveException("The source contains unsafe paths and was rejected")
        }

        val xmlPath = files
            .filter { it == "Project.xml" || it.endsWith("/Project.xml") }
            .minByOrNull { it.length }
        if (xmlPath == null) {
            return Analysis(
                Finding(false, "Unknown"),
                Finding(false, "Unknown"),
                Finding(false, "Unknown"),
                Finding(false, "Project.xml missing"),
                listOf("Project.xml was not found. Make sure the source contains the mod code."),
                0,
                false,
                BuildProfile.Legacy
            )
        }
        val base = xmlPath.removeSuffix("Project.xml")
        val xml = source.text(xmlPath)
        val warnings = ArrayList<String>()

        val hay = (files.take(400).joinToString(" ") + xml).lowercase()
        val engine = ENGINES.firstOrNull { hay.contains(it.first) }
        val engineFinding = Finding(engine != null, engine?.second ?: "Not identified")

        val hmm = files.firstOrNull { it == base + "hmm.json" }
        val setup = files.firstOrNull { it == base + "setup/unix.sh" || it == base + "setup/windows.bat" }
        var unpinned = emptyList<String>()
        if (hmm != null) {
            try {
                val deps = Json.parseToJsonElement(source.text(hmm)).jsonObject["dependencies"]?.jsonArray ?: JsonArray(emptyList())
                unpinned = deps.map { it.jsonObject }
                    .filter { it.text("version") == null && it.text("ref") == null }
                    .mapNotNull { it.text("name") }
            } catch (e: Exception) {
                warnings.add("hmm.json is invalid, dependencies could not be read.")
            }
        } else if (setup == null) {
            warnings.add("No hmm.json or setup script. Libraries will be installed from Project.xml and may fail.")
        }
        if (unpinned.isNotEmpty()) {
            warnings.add("Libraries without a pinned version, they may break: " + unpinned.joinToString(", ") + ".")
        }
        val libsFinding = when {
            unpinned.isNotEmpty() -> Finding(false, "${unpinned.size} unpinned")
            hmm != null -> Finding(true, "Libraries ok")
            setup != null -> Finding(true, "Setup script")
            else -> Finding(false, "No lock file")
        }

        val sources = files.filter { it.endsWith(".hx") }
        var issues = 0
        if (target.mobile) {
            if (DESKTOP_ONLY_DEFINES.containsMatchIn(xml)) {
                warnings.add("Mods, Lua and HScript are enabled only on desktop in Project.xml.")
                issues++
            }
            if (files.none { path -> TOUCH_MARKERS.any { path.lowercase().contains(it) } }) {
                warnings.add("No touch controls found. The mobile build starts but needs a gamepad or keyboard to play.")
                issues++
            }
            for (path in sources.take(source.scanCap)) {
                if (source.size(path) > MAX_SCAN_BYTES) continue
                val code = try {
                    source.text(path)
                } catch (e: ArchiveException) {
                    continue
                }
                for ((regex, message) in MOBILE_RULES) {
                    if (regex.containsMatchIn(code)) {
                        warnings.add(path.removePrefix(base) + ": " + message)
                        issues++
                    }
                }
            }
        }
        val codeFinding = Finding(
            issues == 0,
            if (issues > 0) "$issues warnings" else "${sources.size} files ok"
        )

        val projectFinding = when {
            !xml.contains("</project>", ignoreCase = true) -> {
                warnings.add("The closing project tag was not found in Project.xml. Add the platform settings manually.")
                Finding(false, "Cannot patch")
            }
            target == BuildTarget.ANDROID && xml.contains("target-sdk-version") -> Finding(true, "Already set")
            target == BuildTarget.IOS && Regex("<ios\\s").containsMatchIn(xml) -> Finding(true, "Already set")
            target.mobile -> Finding(true, "Auto patch")
            else -> Finding(true, "No change")
        }

        val modern = engine?.first == "psych" || setup != null
        val profile = BuildProfile(
            haxe = if (modern) MODERN_HAXE else BuildProfile.Legacy.haxe,
            defines = if (engine?.first == "psych") listOf("officialBuild") else emptyList(),
            modern = modern
        )

        val shown = warnings.take(MAX_WARNINGS) + if (warnings.size > MAX_WARNINGS) listOf("And ${warnings.size - MAX_WARNINGS} more warnings.") else emptyList()
        return Analysis(engineFinding, libsFinding, codeFinding, projectFinding, shown, sources.size, true, profile)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
