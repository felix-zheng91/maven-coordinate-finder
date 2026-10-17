package dev.felix.mavensearch.snippet

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.DependencyFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class DependencySnippetBuilderTest {

    private val coordinates = ArtifactCoordinates("com.google.guava", "guava")
    private val version = "33.7.2-jre"

    @Test
    fun `maven xml snippet is ready to paste`() {
        assertEquals(
            """
            <dependency>
                <groupId>com.google.guava</groupId>
                <artifactId>guava</artifactId>
                <version>33.7.2-jre</version>
            </dependency>
            """.trimIndent(),
            DependencySnippetBuilder.build(coordinates, version, DependencyFormat.MAVEN_XML),
        )
    }

    @Test
    fun `gradle groovy snippet uses single quotes`() {
        assertEquals(
            "implementation 'com.google.guava:guava:33.7.2-jre'",
            DependencySnippetBuilder.build(coordinates, version, DependencyFormat.GRADLE_GROOVY),
        )
    }

    @Test
    fun `gradle kotlin snippet uses double quotes`() {
        assertEquals(
            "implementation(\"com.google.guava:guava:33.7.2-jre\")",
            DependencySnippetBuilder.build(coordinates, version, DependencyFormat.GRADLE_KOTLIN),
        )
    }

    @Test
    fun `plain format is g a v`() {
        assertEquals(
            "com.google.guava:guava:33.7.2-jre",
            DependencySnippetBuilder.build(coordinates, version, DependencyFormat.PLAIN),
        )
    }

    @Test
    fun `version build metadata with plus is allowed`() {
        assertEquals(
            "com.google.guava:guava:1.0.0+build.1",
            DependencySnippetBuilder.build(coordinates, "1.0.0+build.1", DependencyFormat.PLAIN),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects coordinates with markup characters`() {
        DependencySnippetBuilder.build(
            ArtifactCoordinates("com.google.guava", "<script>"),
            version,
            DependencyFormat.MAVEN_XML,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects version ranges`() {
        DependencySnippetBuilder.build(
            coordinates,
            "[1.0,2.0)",
            DependencyFormat.MAVEN_XML,
        )
    }
}
