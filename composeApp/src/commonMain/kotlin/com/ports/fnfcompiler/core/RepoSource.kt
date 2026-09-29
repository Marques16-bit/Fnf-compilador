package com.ports.fnfcompiler.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class RepoRef(val owner: String, val name: String, val ref: String?) {
    val slug: String get() = "$owner/$name"

    companion object {
        private val URL = Regex("^(?:https?://)?(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/tree/([^\\s?#]+?))?/?(?:[?#].*)?$")
        private val SHORT = Regex("^([\\w.-]+)/([\\w.-]+?)(?:\\.git)?$")

        fun parse(input: String): RepoRef? {
            val text = input.trim()
            val match = URL.matchEntire(text) ?: SHORT.matchEntire(text) ?: return null
            val groups = match.groupValues
            return RepoRef(groups[1], groups[2], groups.getOrNull(3)?.ifEmpty { null })
        }
    }
}

class RepoSource private constructor(
    val slug: String,
    val ref: String,
    private val sizes: Map<String, Long>,
    private val client: HttpClient,
    private val token: String
) : SourceFiles {
    override val names: List<String> = sizes.keys.toList()
    override val scanCap: Int = 400

    override fun size(name: String): Long = sizes[name] ?: 0L

    override suspend fun text(name: String): String {
        val response = client.get("https://raw.githubusercontent.com/$slug/${ref.encodeURLPath()}/${name.encodeURLPath()}") {
            authorize(token)
        }
        if (response.status.value !in 200..299) throw ArchiveException("Could not read $name (status ${response.status.value})")
        return response.bodyAsText()
    }

    fun close() = client.close()

    companion object {
        suspend fun open(repo: RepoRef, token: String): RepoSource {
            val client = HttpClient { expectSuccess = false }
            try {
                var ref = repo.ref
                if (ref == null) {
                    val info = client.get("https://api.github.com/repos/${repo.slug}") { authorize(token) }
                    ensure(info, repo.slug)
                    ref = (Json.parseToJsonElement(info.bodyAsText()) as? JsonObject)?.text("default_branch")
                        ?: throw ArchiveException("GitHub did not return a default branch for ${repo.slug}")
                }
                val tree = client.get("https://api.github.com/repos/${repo.slug}/git/trees/${ref.encodeURLPath()}?recursive=1") {
                    authorize(token)
                }
                ensure(tree, repo.slug)
                val entries = (Json.parseToJsonElement(tree.bodyAsText()).jsonObject["tree"] as? JsonArray) ?: JsonArray(emptyList())
                val sizes = LinkedHashMap<String, Long>()
                for (item in entries) {
                    val obj = item.jsonObject
                    if (obj.text("type") == "blob") {
                        val path = obj.text("path") ?: continue
                        sizes[path] = (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0L
                    }
                }
                return RepoSource(repo.slug, ref, sizes, client, token)
            } catch (e: Exception) {
                client.close()
                throw e
            }
        }

        private suspend fun ensure(response: HttpResponse, slug: String) {
            val status = response.status.value
            if (status in 200..299) return
            throw ArchiveException(
                when (status) {
                    404 -> "Repository or branch not found: $slug. It must be public."
                    403 -> "GitHub refused the request, probably a rate limit. Add a token and try again."
                    else -> "GitHub answered with error $status for $slug."
                }
            )
        }

        private fun io.ktor.client.request.HttpRequestBuilder.authorize(token: String) {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
