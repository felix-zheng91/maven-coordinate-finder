package dev.felix.mavensearch.source.central

import dev.felix.mavensearch.Fixtures
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ClassMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralJsonParserTest {

    @Test
    fun `parses artifact search response`() {
        val results = CentralJsonParser.parseArtifactSearch(Fixtures.load("keyword-guava.json"))

        assertEquals(5, results.size)
        assertTrue(results.any { it.coordinates.id == "com.google.guava:failureaccess" })
        assertTrue(results.all { it.coordinates.groupId.isNotBlank() })
    }

    @Test
    fun `class search reports exactly matching class names`() {
        val matches = CentralJsonParser.parseClassSearch(
            Fixtures.load("class-gson.json"),
            "com.google.gson.Gson",
        )

        val gson = matches.first { it.coordinates.id == "com.google.code.gson:gson" }
        assertEquals(ClassMatch.Confidence.EXACT, gson.confidence)
        assertEquals(listOf("com.google.gson.Gson"), gson.matchedClassNames)
        assertEquals("2.8.8", gson.matchedVersion)
    }

    @Test
    fun `shaded copies are downgraded to relocated`() {
        val matches = CentralJsonParser.parseClassSearch(
            Fixtures.load("class-lists.json"),
            "com.google.common.collect.Lists",
        )

        // 5 条文档里有 3 个 com.arakelian:retry 的版本，按 g:a 聚合后剩 3 个 artifact
        assertEquals(3, matches.size)
        assertTrue(matches.all { it.confidence == ClassMatch.Confidence.RELOCATED })
        assertTrue(matches.first().matchedClassNames.first().startsWith("repackaged."))
    }

    @Test
    fun `missing highlighting never produces exact matches`() {
        val json = """{"response":{"docs":[{"id":"g:a:1","g":"g","a":"a","v":"1"}]}}"""

        val matches = CentralJsonParser.parseClassSearch(json, "com.foo.Bar")

        assertEquals(1, matches.size)
        assertEquals(ClassMatch.Confidence.RELOCATED, matches.single().confidence)
    }

    @Test
    fun `empty response yields empty list`() {
        assertEquals(
            emptyList<ArtifactSearchResult>(),
            CentralJsonParser.parseArtifactSearch("""{"response":{}}"""),
        )
    }
}
