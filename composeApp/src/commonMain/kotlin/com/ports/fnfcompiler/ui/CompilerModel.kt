package com.ports.fnfcompiler.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ports.fnfcompiler.core.Analysis
import com.ports.fnfcompiler.core.Analyzer
import com.ports.fnfcompiler.core.BuildConfig
import com.ports.fnfcompiler.core.BuildOutcome
import com.ports.fnfcompiler.core.BuildProfile
import com.ports.fnfcompiler.core.BuildSource
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.GitHubBuilder
import com.ports.fnfcompiler.core.RepoRef
import com.ports.fnfcompiler.core.RepoSource
import com.ports.fnfcompiler.core.SettingsStore
import com.ports.fnfcompiler.core.SourceFiles
import com.ports.fnfcompiler.core.ZipSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DEFAULT_REPO = "Marques16-bit/Fnf-compilador"
const val DEFAULT_BRANCH = "main"
const val EXAMPLE_URL = "https://github.com/ShadowMario/FNF-PsychEngine"

enum class Stage(val label: String) {
    Source("Source"),
    Build("Build"),
    Download("Download")
}

class ManualBuild(val tag: String, val pageUrl: String, val values: List<Pair<String, String>>)

class CompilerModel(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val openUrl: (String) -> Unit
) {
    private val saved = store.load()

    var target by mutableStateOf(store.loadTarget())
        private set
    var fromRepo by mutableStateOf(true)
    var repoUrl by mutableStateOf(store.loadUrl())
    var token by mutableStateOf(saved.token)
    var buildRepo by mutableStateOf(saved.repo)
    var branch by mutableStateOf(saved.branch)
    var zip by mutableStateOf<ZipSource?>(null)
        private set
    var analysis by mutableStateOf<Analysis?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var stage by mutableStateOf(0)
        private set
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<BuildOutcome?>(null)
        private set
    var manual by mutableStateOf<ManualBuild?>(null)
        private set
    val log = mutableStateListOf<String>()

    private var loaded: SourceFiles? = null

    val automatic: Boolean get() = token.isNotBlank()

    fun selectTarget(option: BuildTarget) {
        target = option
        store.saveTarget(option)
        val current = loaded
        if (current != null && !busy) scope.launch { analyze(current) }
    }

    fun selectZip(name: String, read: suspend () -> ByteArray) {
        if (busy) return
        scope.launch {
            reset()
            status = "Reading $name"
            try {
                val picked = withContext(Dispatchers.Default) { ZipSource(name, read()) }
                zip = picked
                replaceSource(picked)
                analyze(picked)
            } catch (e: Exception) {
                zip = null
                fail("Could not read the archive: ${e.message}")
            }
        }
    }

    fun check() {
        if (busy) return
        scope.launch {
            busy = true
            reset()
            try {
                val source = loadSource() ?: return@launch
                analyze(source)
            } catch (e: Exception) {
                fail(e.message ?: "Could not read the source")
            } finally {
                busy = false
            }
        }
    }

    fun compile() {
        if (busy) return
        scope.launch {
            busy = true
            reset()
            try {
                val source = loadSource() ?: return@launch
                val result = analyze(source) ?: return@launch
                if (!result.hasProject) {
                    fail("Project.xml was not found in the source")
                    return@launch
                }
                val config = BuildConfig(
                    token.trim(),
                    buildRepo.trim().ifEmpty { DEFAULT_REPO },
                    branch.trim().ifEmpty { DEFAULT_BRANCH }
                )
                store.save(BuildConfig(config.token, buildRepo.trim(), branch.trim()))
                val buildSource = when (source) {
                    is RepoSource -> BuildSource.Repository(source.slug, source.ref)
                    is ZipSource -> BuildSource.Archive(source.fileName, source.bytes)
                    else -> throw IllegalStateException("Unsupported source")
                }
                if (config.token.isEmpty()) {
                    startManual(config, buildSource, result.profile)
                } else {
                    runAutomatic(config, buildSource, result.profile)
                }
            } catch (e: Exception) {
                fail(e.message ?: "The build could not be started. Check your connection and try again")
            } finally {
                busy = false
            }
        }
    }

    fun checkManual() {
        val pending = manual ?: return
        if (busy) return
        scope.launch {
            busy = true
            val builder = GitHubBuilder(BuildConfig("", buildRepo.trim().ifEmpty { DEFAULT_REPO }, branch.trim().ifEmpty { DEFAULT_BRANCH }), ::addLog)
            try {
                val found = builder.lookup(pending.tag)
                if (found?.downloadUrl != null) {
                    outcome = found
                    stage = 3
                    status = "Build ready"
                    openUrl(found.downloadUrl)
                } else {
                    status = "Not ready yet. Run the workflow and check again in a few minutes"
                }
            } catch (e: Exception) {
                status = e.message ?: "Could not reach GitHub"
            } finally {
                builder.close()
                busy = false
            }
        }
    }

    fun openPage(url: String) = openUrl(url)

    private suspend fun runAutomatic(config: BuildConfig, source: BuildSource, profile: BuildProfile) {
        stage = 2
        status = "Building on GitHub Actions"
        val builder = GitHubBuilder(config, ::addLog)
        try {
            val done = builder.build(source, target, profile)
            outcome = done
            if (done.success && done.downloadUrl != null) {
                stage = 3
                status = "Build ready. The download starts automatically"
                openUrl(done.downloadUrl)
            } else if (done.success) {
                fail("The build passed but no executable was found in the release")
            } else {
                fail("The build failed. Read the log below")
            }
        } finally {
            builder.close()
        }
    }

    private fun startManual(config: BuildConfig, source: BuildSource, profile: BuildProfile) {
        if (source !is BuildSource.Repository) {
            fail("Uploading a ZIP needs a GitHub token. Add one in Advanced settings or use a repository URL")
            return
        }
        val tag = GitHubBuilder.newTag()
        val page = "https://github.com/${config.repo}/actions/workflows/build-mod.yml"
        manual = ManualBuild(
            tag,
            page,
            listOf(
                "tag" to tag,
                "target" to target.id,
                "source_repo" to source.repo,
                "source_ref" to source.ref,
                "haxe_version" to profile.haxe,
                "defines" to profile.defines.joinToString(" "),
                "toolchain" to if (profile.modern) "modern" else "legacy"
            )
        )
        stage = 2
        status = "No token set. Run the workflow yourself with the values below"
        openUrl(page)
    }

    private suspend fun loadSource(): SourceFiles? {
        if (!fromRepo) {
            val picked = zip
            if (picked == null) {
                fail("Choose a mod ZIP first")
                return null
            }
            return picked
        }
        val ref = RepoRef.parse(repoUrl)
        if (ref == null) {
            fail("That is not a valid GitHub repository URL")
            return null
        }
        status = "Reading ${ref.slug}"
        val opened = RepoSource.open(ref, token)
        store.saveUrl(repoUrl.trim())
        replaceSource(opened)
        return opened
    }

    private fun replaceSource(next: SourceFiles) {
        val old = loaded
        if (old is RepoSource && old !== next) old.close()
        loaded = next
    }

    private suspend fun analyze(source: SourceFiles): Analysis? {
        status = "Analyzing the project"
        return try {
            val result = withContext(Dispatchers.Default) { Analyzer.analyze(source, target) }
            analysis = result
            stage = 1
            status = if (result.hasProject) "Source is ready" else "Project.xml was not found in the source"
            result
        } catch (e: Exception) {
            fail("Could not analyze the source: ${e.message}")
            null
        }
    }

    private fun reset() {
        log.clear()
        outcome = null
        manual = null
        failed = false
        stage = 0
        status = ""
    }

    private fun fail(message: String) {
        failed = true
        status = message
        addLog(message)
    }

    private fun addLog(line: String) {
        log.add(line)
    }
}
