package com.ports.fnfcompiler

import com.ports.fnfcompiler.core.BuildConfig
import com.ports.fnfcompiler.core.BuildException
import com.ports.fnfcompiler.core.GitHubBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubBuilderTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val config = BuildConfig("token", "owner/repo", "main")
    private val release = """{"assets":[{"name":"result-windows.zip","browser_download_url":"https://example.com/result-windows.zip","size":2097152}]}"""

    @Test
    fun retriesTransientServerErrors() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("busy", HttpStatusCode.ServiceUnavailable) else respond(release, HttpStatusCode.OK, json)
        }
        val builder = GitHubBuilder(config, {}, HttpClient(engine))
        val outcome = builder.lookup("mod-1")
        assertNotNull(outcome)
        assertEquals("result-windows.zip", outcome.fileName)
        assertEquals(3, calls)
    }

    @Test
    fun retriesConnectionFailures() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) throw IllegalStateException("network down") else respond(release, HttpStatusCode.OK, json)
        }
        val outcome = GitHubBuilder(config, {}, HttpClient(engine)).lookup("mod-1")
        assertNotNull(outcome)
        assertEquals(2, calls)
    }

    @Test
    fun returnsNothingWhenTheReleaseHasNoResultYet() = runTest {
        val engine = MockEngine { respond("""{"assets":[]}""", HttpStatusCode.OK, json) }
        assertNull(GitHubBuilder(config, {}, HttpClient(engine)).lookup("mod-1"))
    }

    @Test
    fun resumesAFinishedBuild() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/actions/runs") -> respond(
                    """{"workflow_runs":[{"id":9,"run_number":4,"status":"completed","conclusion":"success","html_url":"https://example.com/run","display_title":"Build windows mod-1"}]}""",
                    HttpStatusCode.OK,
                    json
                )
                path.endsWith("/releases/tags/mod-1") -> respond(release, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val lines = ArrayList<String>()
        val outcome = GitHubBuilder(config, { lines.add(it) }, HttpClient(engine)).resume("mod-1", com.ports.fnfcompiler.core.BuildTarget.WINDOWS)
        assertTrue(outcome.success)
        assertEquals("https://example.com/result-windows.zip", outcome.downloadUrl)
        assertEquals("https://example.com/run", outcome.runUrl)
        assertTrue(lines.any { it.contains("Reconnecting") })
    }

    @Test
    fun failsClearlyWhenTheRunCannotBeFound() = runTest {
        val engine = MockEngine { respond("""{"workflow_runs":[]}""", HttpStatusCode.OK, json) }
        assertFailsWith<BuildException> {
            GitHubBuilder(config, {}, HttpClient(engine)).resume("mod-1", com.ports.fnfcompiler.core.BuildTarget.WINDOWS)
        }
    }
}
