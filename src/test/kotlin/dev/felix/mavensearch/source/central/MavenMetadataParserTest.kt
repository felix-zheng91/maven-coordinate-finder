package dev.felix.mavensearch.source.central

import dev.felix.mavensearch.Fixtures
import dev.felix.mavensearch.model.ArtifactCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenMetadataParserTest {

    private val coordinates = ArtifactCoordinates("com.google.guava", "guava")

    @Test
    fun `parses versions and marks the release`() {
        val list = MavenMetadataParser.parse(Fixtures.load("guava-metadata.xml"), coordinates)

        assertEquals("33.7.2-jre", list.latest)
        assertEquals("33.7.2-jre", list.release)
        assertEquals(160, list.versions.size)
        // metadata 是发布顺序，解析后应新版本在前
        assertEquals("33.7.2-jre", list.versions.first().version)
        assertTrue(list.versions.first().isRelease)
        assertEquals("r03", list.versions.last().version)
    }

    @Test
    fun `rejects doctype declarations to prevent xxe`() {
        val malicious = """
            <?xml version="1.0"?>
            <!DOCTYPE metadata [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <metadata><versioning><versions><version>&xxe;</version></versions></versioning></metadata>
        """.trimIndent()

        val error = assertThrows(Exception::class.java) {
            MavenMetadataParser.parse(malicious, coordinates)
        }
        assertTrue(
            "expected a DOCTYPE rejection, got: ${error.message}",
            error.message?.contains("DOCTYPE", ignoreCase = true) == true,
        )
    }
}
