package dev.felix.mavensearch.search

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ClassMatch
import dev.felix.mavensearch.verify.ClassVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassFallbackSearchTest {

    @Test
    fun `keeps only artifacts whose jar contains the class`() = runBlocking {
        val queried = mutableListOf<String>()
        val fallback = ClassFallbackSearch(
            keywordSearch = { query, _ ->
                queried += query
                listOf(
                    ArtifactSearchResult(
                        ArtifactCoordinates("com.example", "good"),
                        latestVersion = "1.0",
                    ),
                    ArtifactSearchResult(
                        ArtifactCoordinates("com.example", "bad"),
                        latestVersion = "2.0",
                    ),
                )
            },
            releaseResolver = { null },
            verifier = ClassVerifier { coordinates, _, _ -> coordinates.artifactId == "good" },
        )

        val result = fallback.search("com.example.Outer\$Inner", limit = 10)

        assertEquals(listOf("Outer"), queried)
        assertEquals(1, result.size)
        assertEquals("good", result.single().coordinates.artifactId)
        assertEquals(ClassMatch.Confidence.EXACT, result.single().confidence)
        assertEquals("1.0", result.single().matchedVersion)
    }

    @Test
    fun `resolves the release version when a candidate has none`() = runBlocking {
        val fallback = ClassFallbackSearch(
            keywordSearch = { _, _ ->
                listOf(ArtifactSearchResult(ArtifactCoordinates("g", "a")))
            },
            releaseResolver = { "9.9.9" },
            verifier = ClassVerifier { _, version, _ -> version == "9.9.9" },
        )

        val result = fallback.search("g.Foo", limit = 10)

        assertEquals("9.9.9", result.single().matchedVersion)
    }

    @Test
    fun `returns empty when the keyword search fails`() = runBlocking {
        val fallback = ClassFallbackSearch(
            keywordSearch = { _, _ -> throw RuntimeException("boom") },
            releaseResolver = { null },
            verifier = ClassVerifier { _, _, _ -> true },
        )

        assertTrue(fallback.search("g.Foo", limit = 10).isEmpty())
    }
}
