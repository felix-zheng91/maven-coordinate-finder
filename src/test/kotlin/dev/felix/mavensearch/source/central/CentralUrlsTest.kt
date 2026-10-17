package dev.felix.mavensearch.source.central

import dev.felix.mavensearch.model.ArtifactCoordinates
import org.junit.Assert.assertEquals
import org.junit.Test

class CentralUrlsTest {

    @Test
    fun `bare keyword is escaped but unchanged`() {
        assertEquals("guava", CentralUrls.keywordQuery("guava"))
        assertEquals("spring boot", CentralUrls.keywordQuery("  spring boot  "))
    }

    @Test
    fun `group artifact form becomes a fielded query`() {
        assertEquals(
            "g:\"com.google.guava\" AND a:\"guava\"",
            CentralUrls.keywordQuery("com.google.guava:guava"),
        )
    }

    @Test
    fun `lucene special characters are escaped`() {
        assertEquals("a\\+b", CentralUrls.keywordQuery("a+b"))
        assertEquals("spring\\:boot", CentralUrls.escapeLucene("spring:boot"))
        assertEquals("\\(a\\)", CentralUrls.escapeLucene("(a)"))
    }

    @Test
    fun `version list url uses group path`() {
        assertEquals(
            "https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml",
            CentralUrls.versionListUrl(ArtifactCoordinates("com.google.guava", "guava")),
        )
    }
}
