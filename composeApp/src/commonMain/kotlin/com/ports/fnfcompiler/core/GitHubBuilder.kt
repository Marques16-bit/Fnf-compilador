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
import kotlinx.coroutines.delay
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

class GitHubBuilder(
    private val config: BuildConfig,
    private val log: (String) -> Unit
) {
    private val client = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 30_000
        }
    }

    fun close() = client.close()

    suspend fun build(zip: ByteArray, fileName: String, target: BuildTarget): BuildOutcome {
        val tag = "mod-" + Random.nextLong().toULong().toString(16)
        log("Creating package $tag")
        var response = api(HttpMethod.Post, "/repos/${config.repo}/releases", buildJsonObject {
            put("tag_name", tag)
            put("name", tag)
            put("prerelease", true)
            put("target_commitish", config.branch)
        }.toString())
        if (response.status.value != 201) throw BuildException(explain(response))
        val releaseId = json(response).obj().long("id") ?: throw BuildException("GitHub returned an invalid release")

        log("Uploading $fileName (${megabytes(zip.size.toLong())}). Keep the app open")
        response = client.request("https://uploads.github.com/repos/${config.repo}/releases/$releaseId/assets?name=mod.zip") {
            method = HttpMethod.Post
            authorize()
            contentType(ContentType.Application.Zip)
            setBody(zip)
        }
        if (response.status.value !in 200..299) throw BuildException("Upload failed with status ${response.status.value}")

        log("Starting the ${target.label} build")
        response = api(HttpMethod.Post, "/repos/${config.repo}/actions/workflows/build-mod.yml/dispatches", buildJsonObject {
            put("ref", config.branch)
            put("inputs", buildJsonObject {
                put("tag", tag)
                put("target", target.id)
            })
        }.toString())
        if (response.status.value != 204) throw BuildException(explain(response))

        val title = "Build ${target.id} $tag"
        var run: JsonObject? = null
        repeat(20) {
            if (run != null) return@repeat
            delay(4_000)
            val list = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs?event=workflow_dispatch&per_page=20")
            if (list.status.value == 200) {
                run = json(list).obj().array("workflow_runs")
                    .map { it.jsonObject }
                    .firstOrNull { it.text("display_title") == title || it.text("name") == title }
            }
        }
        var current = run ?: throw BuildException("GitHub did not start the build in time. Check the Actions tab")
        log("Build #${current.long("run_number")} started")

        val seen = HashSet<String>()
        while (current.text("status") != "completed") {
            delay(6_000)
            val poll = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs/${current.long("id")}")
            if (poll.status.value != 200) continue
            current = json(poll).obj()
            val jobs = api(HttpMethod.Get, "/repos/${config.repo}/actions/runs/${current.long("id")}/jobs")
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
        }
        return finish(current, tag)
    }

    private suspend fun finish(run: JsonObject, tag: String): BuildOutcome {
        val runUrl = run.text("html_url")
        val conclusion = run.text("conclusion") ?: "unknown"
        if (conclusion == "success") {
            log("Build finished successfully")
            val release = api(HttpMethod.Get, "/repos/${config.repo}/releases/tags/$tag")
            if (release.status.value == 200) {
                val asset = json(release).obj().array("assets").map { it.jsonObject }
                    .firstOrNull { it.text("name")?.startsWith("result-") == true }
                if (asset != null) {
                    return BuildOutcome(true, runUrl, asset.text("browser_download_url"), asset.text("name"), asset.long("size") ?: 0L)
                }
            }
            log("The build passed but no result file was found in the release")
            return BuildOutcome(true, runUrl, null, null, 0L)
        }
        log("Build ended as: ${describe(conclusion)}")
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
        return BuildOutcome(false, runUrl, null, null, 0L)
    }

    private suspend fun api(method: HttpMethod, path: String, body: String? = null): HttpResponse =
        client.request("https://api.github.com$path") {
            this.method = method
            authorize()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("Authorization", "Bearer ${config.token}")
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
