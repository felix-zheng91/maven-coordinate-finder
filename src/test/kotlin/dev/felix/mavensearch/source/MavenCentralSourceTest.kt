package dev.felix.mavensearch.source

import dev.felix.mavensearch.Fixtures
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ClassMatch
import dev.felix.mavensearch.net.BinaryRange
import dev.felix.mavensearch.net.HttpException
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.net.HttpResult
import dev.felix.mavensearch.source.central.CentralUrls
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenCentralSourceTest {

    private fun source(http: HttpFetcher) = MavenCentralSource(
        http = http,
        primaryEndpoint = "https://primary.test/solr",
        fallbackEndpoint = "https://fallback.test/solr",
        id = "test",
        displayName = "Test",
    )

    @Test
    fun `keyword search hits the primary endpoint and parses the response`() = runBlocking {
        val http = FakeHttpFetcher(
            mapOf("https://primary.test/solr" to Fixtures.load("keyword-guava.json")),
        )

        val results = source(http).searchArtifacts("guava", limit = 20)

        assertEquals(5, results.size)
        assertTrue(results.any { it.coordinates.id == "com.google.guava:failureaccess" })
        val url = http.requested.single()
        assertTrue(url.startsWith("https://primary.test/solr?"))
        assertTrue(url.contains("rows=20"))
        assertTrue(url.contains("q=guava"))
    }

    @Test
    fun `falls back to the secondary endpoint when the primary fails`() = runBlocking {
        val http = FakeHttpFetcher(
            responses = mapOf("https://fallback.test/solr" to Fixtures.load("keyword-guava.json")),
            failingPrefixes = setOf("https://primary.test/solr"),
        )

        val results = source(http).searchArtifacts("guava", limit = 5)

        assertEquals(5, results.size)
        assertEquals(2, http.requested.size)
        assertTrue(http.requested[1].startsWith("https://fallback.test/solr?"))
    }

    @Test
    fun `class search verifies matches through highlighting`() = runBlocking {
        val http = FakeHttpFetcher(
            mapOf(CentralUrls.LEGACY_SOLR to Fixtures.load("class-gson.json")),
        )

        val results = source(http).searchByClassName("com.google.gson.Gson", limit = 5)

        val gson = results.first { it.coordinates.id == "com.google.code.gson:gson" }
        assertEquals(ClassMatch.Confidence.EXACT, gson.confidence)
        assertTrue(gson.matchedClassNames.contains("com.google.gson.Gson"))
    }

    @Test
    fun `shaded copies never surface as exact matches`() = runBlocking {
        val http = FakeHttpFetcher(
            mapOf(CentralUrls.LEGACY_SOLR to Fixtures.load("class-lists.json")),
        )

        val results = source(http).searchByClassName("com.google.common.collect.Lists", limit = 5)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.confidence == ClassMatch.Confidence.RELOCATED })
    }

    @Test
    fun `class index failure surfaces as a dedicated exception`() {
        val http = FakeHttpFetcher(emptyMap())

        assertThrows(ClassIndexUnavailableException::class.java) {
            runBlocking { source(http).searchByClassName("com.foo.Bar", limit = 5) }
        }
    }

    @Test
    fun `version list is read from maven metadata`() = runBlocking {
        val http = FakeHttpFetcher(
            mapOf(CentralUrls.REPO1 to Fixtures.load("guava-metadata.xml")),
        )

        val list = source(http).listVersions(ArtifactCoordinates("com.google.guava", "guava"))

        assertEquals("33.7.2-jre", list.release)
        assertEquals("33.7.2-jre", list.versions.first().version)
        assertTrue(list.versions.first().isRelease)
    }

    private class FakeHttpFetcher(
        private val responses: Map<String, String>,
        private val failingPrefixes: Set<String> = emptySet(),
    ) : HttpFetcher {

        val requested = mutableListOf<String>()

        override suspend fun get(url: String, accept: String, timeoutMs: Int): HttpResult {
            requested += url
            if (failingPrefixes.any { url.startsWith(it) }) {
                throw HttpException("simulated failure")
            }
            val body = responses.entries.firstOrNull { url.startsWith(it.key) }?.value
                ?: return HttpResult(404, "")
            return HttpResult(200, body)
        }

        override suspend fun getSuffix(
            url: String,
            suffixBytes: Int,
            timeoutMs: Int,
        ): BinaryRange = BinaryRange(ByteArray(0), 0, 0, 404)

        override suspend fun getRange(
            url: String,
            start: Long,
            end: Long,
            timeoutMs: Int,
        ): BinaryRange = BinaryRange(ByteArray(0), 0, 0, 404)
    }
}
