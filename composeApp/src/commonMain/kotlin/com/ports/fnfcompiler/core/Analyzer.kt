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
    val hasProject: Boolean
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

    private const val MAX_SCAN_BYTES = 500_000L
    private const val MAX_SCAN_FILES = 1500
    private const val MAX_WARNINGS = 60

    fun analyze(bytes: ByteArray, target: BuildTarget): Analysis {
        val zip = ZipArchive(bytes)
        val files = zip.entries.filter { !it.isDirectory }
        if (files.any { it.name.contains("..") || it.name.startsWith("/") }) {
            throw ArchiveException("The archive contains unsafe paths and was rejected")
        }

        val xmlEntry = files
            .filter { it.name == "Project.xml" || it.name.endsWith("/Project.xml") }
            .minByOrNull { it.name.length }
        if (xmlEntry == null) {
            return Analysis(
                Finding(false, "Unknown"),
                Finding(false, "Unknown"),
                Finding(false, "Unknown"),
                Finding(false, "Project.xml missing"),
                listOf("Project.xml was not found. Make sure the archive contains the mod source code."),
                0,
                false
            )
        }
        val base = xmlEntry.name.removeSuffix("Project.xml")
        val xml = zip.readText(xmlEntry)
        val warnings = ArrayList<String>()

        val hay = (files.take(400).joinToString(" ") { it.name } + xml).lowercase()
        val engine = ENGINES.firstOrNull { hay.contains(it.first) }
        val engineFinding = Finding(engine != null, engine?.second ?: "Not identified")

        val hmm = files.firstOrNull { it.name == base + "hmm.json" }
        var unpinned = emptyList<String>()
        if (hmm != null) {
            try {
                val deps = Json.parseToJsonElement(zip.readText(hmm)).jsonObject["dependencies"]?.jsonArray ?: JsonArray(emptyList())
                unpinned = deps.map { it.jsonObject }.filter { it.text("version") == null && it.text("ref") == null }
                    .mapNotNull { it.text("name") }
            } catch (e: Exception) {
                warnings.add("hmm.json is invalid, dependencies could not be read.")
            }
        } else {
            warnings.add("hmm.json is missing. Dependencies may fail during the build.")
        }
        if (unpinned.isNotEmpty()) {
            warnings.add("Libraries without a pinned version, they may break: " + unpinned.joinToString(", ") + ".")
        }
        val libsFinding = Finding(
            unpinned.isEmpty() && hmm != null,
            if (unpinned.isNotEmpty()) "${unpinned.size} unpinned" else if (hmm == null) "No hmm.json" else "Libraries ok"
        )

        val sources = files.filter { it.name.endsWith(".hx") }.take(MAX_SCAN_FILES)
        var issues = 0
        if (target.mobile) {
            for (entry in sources) {
                if (entry.size > MAX_SCAN_BYTES) continue
                val code = try {
                    zip.readText(entry)
                } catch (e: ArchiveException) {
                    continue
                }
                for ((regex, message) in MOBILE_RULES) {
                    if (regex.containsMatchIn(code)) {
                        warnings.add(entry.name.removePrefix(base) + ": " + message)
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

        return Analysis(engineFinding, libsFinding, codeFinding, projectFinding, warnings.take(MAX_WARNINGS) + extra(warnings.size), sources.size, true)
    }

    private fun extra(total: Int): List<String> =
        if (total > MAX_WARNINGS) listOf("And ${total - MAX_WARNINGS} more warnings.") else emptyList()

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
