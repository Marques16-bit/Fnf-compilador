package com.ports.fnfcompiler.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

data class BuildConfig(val token: String, val repo: String, val branch: String)

data class BuildOutcome(
    val success: Boolean,
    val runUrl: String?,
    val downloadUrl: String?,
    val fileName: String?,
    val sizeBytes: Long
)

class BuildException(message: String) : Exception(message)

sealed interface BuildSource {
    data class Archive(val name: String, val bytes: ByteArray) : BuildSource
    data class Repository(val repo: String, val ref: String) : BuildSource
}

private fun defaultClient() = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = 30_000
    }
}

class GitHubBuilder(
    private val config: BuildConfig,
    private val log: (String) -> Unit,
    private val client: HttpClient = defaultClient()
) {
    private var runId: Long? = null

    fun close() = client.close()

    companion object {
        private const val API_ATTEMPTS = 4
        private const val BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val FIND_ATTEMPTS = 20
        private const val RESUME_FIND_ATTEMPTS = 3
        private const val FIND_DELAY_MS = 4_000L
        private const val POLL_DELAY_MS = 6_000L
        private const val MAX_POLLS = 3_600
        private const val MAX_POLL_FAILURES = 20

        fun newTag(): String = "mod-" + Random.nextLong().toULong().toString(16)
    }

    suspend fun lookup(tag: String): BuildOutcome? {
        val release = api(HttpMethod.Get, "/repos/${config.repo}/releases/tags/$tag")
        if (release.status.value != 200) return null
        val asset = json(release).obj().array("assets").map { it.jsonObject }
            .firstOrNull { it.text("name")?.startsWith("result-") == true } ?: return null
        return BuildOutcome(true, null, asset.text("browser_download_url"), asset.text("name"), asset.long("size") ?: 0L)
    }

    suspend fun build(
        source: BuildSource,
        target: BuildTarget,
        profile: BuildProfile,
        tag: String = newTag(),
        onDispatched: (String) -> Unit = {}
    ): BuildOutcome {
        log("Creating package $tag")
        var response = api(HttpMethod.Post, "/repos/${config.repo}/releases", buildJsonObject {
            put("tag_name", tag)
            put("name", tag)
            put("prerelease", true)
            put("target_commitish", config.branch)
        }.toString())
        if (response.status.value != 201) throw BuildException(explain(response))
        val releaseId = json(response).obj().long("id") ?: throw BuildException("GitHub returned an invalid release")

        if (source is BuildSource.Archive) {
            log("Uploading ${source.name} (${megabytes(source.bytes.size.toLong())}). Keep the app open")
            response = client.request("https://uploads.github.com/repos/${config.repo}/releases/$releaseId/assets?name=mod.zip") {
                method = HttpMethod.Post
                authorize()
                contentType(ContentType.Application.Zip)
                setBody(source.bytes)
            }
            if (response.status.value !in 200..299) throw BuildException("Upload failed with status ${response.status.value}")
        } else if (source is BuildSource.Repository) {
            log("Source: ${source.repo} at ${source.ref}")
        }

        log("Starting the ${target.label} build")
        response = api(HttpMethod.Post, "/repos/${config.repo}/actions/workflows/build-mod.yml/dispatches", buildJsonObject {
            put("ref", config.branch)
            put("inputs", buildJsonObject {
                put("tag", tag)
                put("target", target.id)
                put("source_repo", (source as? BuildSource.Repository)?.repo ?: "")
                put("source_ref", (source as? BuildSource.Repository)?.ref ?: "")
                put("haxe_version", profile.haxe)
                put("defines", profile.defines.joinToString(" "))
                put("toolchain", if (profile.modern) "modern" else "legacy")
                put("recipe", profile.recipe)
            })
        }.toString())
        if (response.status.value != 204) throw BuildException(explain(response))
        onDispatched(tag)
        return track(tag, target, FIND_ATTEMPTS)
    }

    suspend fun resume(tag: String, target: BuildTarget): BuildOutcome {
        log("Reconnecting to build $tag")
        return track(tag, target, RESUME_FIND_ATTEMPTS)
    }

    suspend fun cancelRun() {
        val id = runId ?: return
        withContext(NonCancellable) {
            try {
                api(HttpMethod.Post, "/repos/${config.repo}/actions/runs/$id/cancel")
                log("Cancellation requested")
            } catch (e: Exception) {
                log("Could not cancel the run: ${e.message}")
            }
        }
    }

    private suspend fun track(tag: String, target: BuildTarget, findAttempts: Int): BuildOutcome {
        val title = "Build ${target.id} $tag"
        var found: JsonObject? = null
        var attempt = 0
        while (found == null && attempt < findAttempts) {
            attempt++
            if (attempt > 1 || findAttempts == FIND_ATTEMPTS) delay(FIND_DELAY_MS)
            found = safely {
                val list = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs?event=workflow_dispatch&per_page=50")
                if (list.status.value == 200) {
                    json(list).obj().array("workflow_runs").map { it.jsonObject }
                        .firstOrNull { it.text("display_title") == title || it.text("name") == title }
                } else {
                    null
                }
            }
        }
        var current = found ?: throw BuildException("GitHub did not start the build in time. Check the Actions tab")
        runId = current.long("id")
        log("Build #${current.long("run_number")} started")

        val seen = HashSet<String>()
        var failures = 0
        var polls = 0
        while (current.text("status") != "completed") {
            if (++polls > MAX_POLLS) throw BuildException("The build ran for more than six hours. Check the Actions tab")
            delay(POLL_DELAY_MS)
            val latest = current
            val update = safely {
                val poll = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs/${latest.long("id")}")
                if (poll.status.value != 200) return@safely null
                val fresh = json(poll).obj()
                val jobs = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs/${fresh.long("id")}/jobs")
                if (jobs.status.value == 200) {
                    for (job in json(jobs).obj().array("jobs")) {
                        val jobObj = job.jsonObject
                        for (step in jobObj.array("steps")) {
                            val s = step.jsonObject
                            val state = s.text("conclusion") ?: s.text("status") ?: continue
                            if (state == "queued") continue
                            val key = "${jobObj.long("id")}:${s.long("number")}:$state"
                            if (seen.add(key)) log("${s.text("name")}: ${describe(state)}")
                        }
                    }
                }
                fresh
            }
            if (update == null) {
                failures++
                if (failures == 1) log("Connection problem, retrying")
                if (failures >= MAX_POLL_FAILURES) {
                    throw BuildException("Lost the connection to GitHub. The build keeps running, open the app again to reconnect")
                }
            } else {
                if (failures > 0) log("Connection restored")
                failures = 0
                current = update
            }
        }
        return finish(current, tag)
    }

    private suspend fun finish(run: JsonObject, tag: String): BuildOutcome {
        val runUrl = run.text("html_url")
        val conclusion = run.text("conclusion") ?: "unknown"
        if (conclusion == "success") {
            log("Build finished successfully")
            val outcome = safely { lookup(tag) }
            if (outcome != null) return outcome.copy(runUrl = runUrl)
            log("The build passed but no result file was found in the release")
            return BuildOutcome(true, runUrl, null, null, 0L)
        }
        log("Build ended as: ${describe(conclusion)}")
        safely {
            val jobs = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs/${run.long("id")}/jobs")
            if (jobs.status.value == 200) {
                val failed = json(jobs).obj().array("jobs").map { it.jsonObject }.firstOrNull { it.text("conclusion") == "failure" }
                if (failed != null) {
                    val logs = api(HttpMethod.Get, "/repos/${config.repo}/actions/jobs/${failed.long("id")}/logs")
                    if (logs.status.value in 200..299) {
                        log("End of the log:\n" + logs.bodyAsText().lines().takeLast(40).joinToString("\n"))
                    } else {
                        log("Open the build page to read the full log")
                    }
                }
            }
            Unit
        }
        return BuildOutcome(false, runUrl, null, null, 0L)
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun api(method: HttpMethod, path: String, body: String? = null): HttpResponse {
        val attempts = if (method == HttpMethod.Get) API_ATTEMPTS else 1
        var attempt = 0
        while (true) {
            attempt++
            try {
                val response = client.request("https://api.github.com$path") {
                    this.method = method
                    authorize()
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
                val status = response.status.value
                val limited = status == 403 && response.headers["X-RateLimit-Remaining"] == "0"
                val retryable = status >= 500 || status == 429 || limited
                if (!retryable || attempt >= attempts) return response
                val wait = response.headers["Retry-After"]?.toLongOrNull()?.times(1000) ?: (BACKOFF_MS shl (attempt - 1))
                delay(minOf(wait, MAX_BACKOFF_MS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= attempts) throw e
                delay(minOf(BACKOFF_MS shl (attempt - 1), MAX_BACKOFF_MS))
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}")
    }

    private suspend fun json(response: HttpResponse): JsonElement = Json.parseToJsonElement(response.bodyAsText())

    private suspend fun explain(response: HttpResponse): String {
        val status = response.status.value
        val detail = try {
            json(response).obj().text("message")
        } catch (e: Exception) {
            null
        }
        val text = when (status) {
            401, 403 -> "Token rejected or missing permissions. It needs Contents and Actions with read and write access on this repository."
            404 -> "Repository or workflow not found. Check the repository name, build-mod.yml in .github/workflows and the branch."
            422 -> "GitHub rejected the request. Check the branch and that the workflow has workflow_dispatch."
            else -> "GitHub answered with error $status."
        }
        return text + " [GitHub $status" + (if (detail != null) ": $detail" else "") + "]"
    }

    private fun describe(state: String): String = when (state) {
        "in_progress" -> "running"
        "success" -> "ok"
        "failure" -> "FAILED"
        "skipped" -> "skipped"
        "cancelled" -> "cancelled"
        "completed" -> "completed"
        else -> state
    }

    private fun megabytes(bytes: Long): String {
        val tenths = bytes * 10 / 1_048_576
        return "${tenths / 10}.${tenths % 10} MB"
    }

    private fun JsonElement.obj(): JsonObject = jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
}
