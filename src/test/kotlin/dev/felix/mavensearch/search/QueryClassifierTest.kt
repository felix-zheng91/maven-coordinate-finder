package dev.felix.mavensearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryClassifierTest {

    @Test
    fun `fully qualified class names are detected`() {
        assertEquals(
            QueryIntent.ClassName("com.google.common.collect.Lists"),
            QueryClassifier.classify("com.google.common.collect.Lists"),
        )
        assertEquals(
            QueryIntent.ClassName("com.google.gson.Gson"),
            QueryClassifier.classify("  com.google.gson.Gson  "),
        )
        assertEquals(
            QueryIntent.ClassName("com.foo.Outer\$Inner"),
            QueryClassifier.classify("com.foo.Outer\$Inner"),
        )
    }

    @Test
    fun `coordinate form wins over class name`() {
        assertEquals(
            QueryIntent.Coordinates(
                dev.felix.mavensearch.model.ArtifactCoordinates("com.google.guava", "guava"),
            ),
            QueryClassifier.classify("com.google.guava:guava"),
        )
    }

    @Test
    fun `plain keywords stay keywords`() {
        assertEquals(QueryIntent.Keyword("guava"), QueryClassifier.classify("guava"))
        assertEquals(QueryIntent.Keyword("spring boot"), QueryClassifier.classify("spring boot"))
    }

    @Test
    fun `dots without an uppercase final segment are not class names`() {
        assertEquals(QueryIntent.Keyword("spring.boot"), QueryClassifier.classify("spring.boot"))
        assertEquals(QueryIntent.Keyword("1.2.3"), QueryClassifier.classify("1.2.3"))
    }

    @Test
    fun `blank input yields empty keyword`() {
        val intent = QueryClassifier.classify("   ")
        assertTrue(intent is QueryIntent.Keyword)
        assertEquals("", (intent as QueryIntent.Keyword).keyword)
    }
}
