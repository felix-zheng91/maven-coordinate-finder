package dev.felix.mavensearch.snippet

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.DependencyFormat

/**
 * 生成可直接粘贴的 dependency 片段。
 *
 * 坐标来自远端接口，属于不可信输入：先过白名单再拼进构建文件，
 * 避免把奇怪字符带进用户的 pom.xml。
 */
object DependencySnippetBuilder {

    private val COORDINATE_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9_.\\-]*$")

    /** 版本号额外允许 `+`（如 `1.0.0+build.1`），其余仍是白名单。 */
    private val VERSION_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9_.+\\-]*$")

    fun build(
        coordinates: ArtifactCoordinates,
        version: String,
        format: DependencyFormat,
    ): String {
        requireSafe(coordinates.groupId, COORDINATE_SAFE, "groupId")
        requireSafe(coordinates.artifactId, COORDINATE_SAFE, "artifactId")
        requireSafe(version, VERSION_SAFE, "version")

        val gav = "${coordinates.id}:$version"
        return when (format) {
            DependencyFormat.MAVEN_XML -> buildMavenXml(coordinates, version)
            DependencyFormat.GRADLE_GROOVY -> "implementation '$gav'"
            DependencyFormat.GRADLE_KOTLIN -> "implementation(\"$gav\")"
            DependencyFormat.PLAIN -> gav
        }
    }

    private fun buildMavenXml(coordinates: ArtifactCoordinates, version: String): String = buildString {
        append("<dependency>\n")
        append("    <groupId>").append(xmlEscape(coordinates.groupId)).append("</groupId>\n")
        append("    <artifactId>").append(xmlEscape(coordinates.artifactId)).append("</artifactId>\n")
        append("    <version>").append(xmlEscape(version)).append("</version>\n")
        append("</dependency>")
    }

    private fun requireSafe(value: String, pattern: Regex, field: String) {
        require(value.isNotBlank() && pattern.matches(value)) {
            "$field contains illegal characters: '$value'"
        }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
