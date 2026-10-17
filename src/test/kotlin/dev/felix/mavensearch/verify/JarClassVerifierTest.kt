package dev.felix.mavensearch.verify

import dev.felix.mavensearch.TestJar
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.net.BinaryRange
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarClassVerifierTest {

    private val coordinates = ArtifactCoordinates("com.example", "demo")

    @Test
    fun `confirms a class using the tail window`() = runBlocking {
        val jar = TestJar.create(mapOf("com/example/Foo.class" to "x"))
        val http = JarFakeHttp(jar)

        assertTrue(JarClassVerifier(http).contains(coordinates, "1.0", "com.example.Foo"))
        assertTrue(http.suffixRequests > 0)
    }

    @Test
    fun `rejects a class that is not in the jar`() = runBlocking {
        val jar = TestJar.create(mapOf("com/example/Foo.class" to "x"))

        assertFalse(
            JarClassVerifier(JarFakeHttp(jar))
                .contains(coordinates, "1.0", "com.example.Missing"),
        )
    }

    @Test
    fun `fetches the central directory separately when it exceeds the tail window`() = runBlocking {
        val jar = TestJar.manyEntries(1500)
        val http = JarFakeHttp(jar)

        assertTrue(
            JarClassVerifier(http)
                .contains(coordinates, "1.0", "com.example.Entry1000"),
        )
        assertTrue(http.rangeRequests > 0)
    }

    @Test
    fun `treats a missing jar as unverified`() = runBlocking {
        val http = JarFakeHttp(ByteArray(0), status = 404)

        assertFalse(
            JarClassVerifier(http).contains(coordinates, "1.0", "com.example.Foo"),
        )
    }

    private class JarFakeHttp(
        private val bytes: ByteArray,
        private val status: Int = 206,
    ) : HttpFetcher {

        var suffixRequests = 0
        var rangeRequests = 0

        override suspend fun get(url: String, accept: String, timeoutMs: Int): HttpResult =
            HttpResult(404, "")

        override suspend fun getSuffix(
            url: String,
            suffixBytes: Int,
            timeoutMs: Int,
        ): BinaryRange {
            suffixRequests++
            if (status !in 200..299) return BinaryRange(ByteArray(0), 0, 0, status)

            val size = minOf(suffixBytes, bytes.size)
            val start = bytes.size - size
            return BinaryRange(
                bytes.copyOfRange(start, bytes.size),
                start.toLong(),
                bytes.size.toLong(),
                206,
            )
        }

        override suspend fun getRange(
            url: String,
            start: Long,
            end: Long,
            timeoutMs: Int,
        ): BinaryRange {
            rangeRequests++
            val from = start.toInt()
            val to = (end.toInt() + 1).coerceAtMost(bytes.size)
            return BinaryRange(bytes.copyOfRange(from, to), start, bytes.size.toLong(), 206)
        }
    }
}
