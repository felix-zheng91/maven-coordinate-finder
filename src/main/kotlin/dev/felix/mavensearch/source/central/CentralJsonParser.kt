package dev.felix.mavensearch.source.central

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ClassMatch

/**
 * Solr 响应的纯函数解析器。刻意用 Gson 树模型 + 手写空值兜底：
 * 对方字段随时可能缺、可能改名，强类型反序列化只会把问题变成异常。
 */
object CentralJsonParser {

    fun parseArtifactSearch(json: String): List<ArtifactSearchResult> =
        docs(root(json)).mapNotNull { element ->
            val obj = element.asJsonObject
            val groupId = obj.stringOrNull("g") ?: return@mapNotNull null
            val artifactId = obj.stringOrNull("a") ?: return@mapNotNull null
            ArtifactSearchResult(
                coordinates = ArtifactCoordinates(groupId, artifactId),
                latestVersion = obj.stringOrNull("latestVersion"),
                packaging = obj.stringOrNull("p"),
                versionCount = obj.intOrNull("versionCount"),
            )
        }

    /**
     * 类名反查结果。
     *
     * 必须由调用方在请求里带 `hl=true&hl.fl=fch`，否则拿不到 `<em>` 高亮片段，
     * 所有结果都会被判为 RELOCATED（宁可不展示，也不给错坐标）。
     */
    fun parseClassSearch(json: String, query: String): List<ClassMatch> {
        val root = root(json)
        val highlighting = root.objectOrNull("highlighting")

        data class Hit(
            val coordinates: ArtifactCoordinates,
            val names: List<String>,
            val version: String?,
            val timestamp: Long,
        )

        val hits = docs(root).mapNotNull { element ->
            val obj = element.asJsonObject
            val groupId = obj.stringOrNull("g") ?: return@mapNotNull null
            val artifactId = obj.stringOrNull("a") ?: return@mapNotNull null
            val names = highlighting
                ?.objectOrNull(obj.stringOrNull("id").orEmpty())
                ?.arrayOrNull("fch")
                ?.map { it.asString.stripEmTags() }
                .orEmpty()
            Hit(
                coordinates = ArtifactCoordinates(groupId, artifactId),
                names = names,
                version = obj.stringOrNull("v"),
                timestamp = obj.longOrNull("timestamp") ?: 0L,
            )
        }

        return hits.groupBy { it.coordinates }.map { (coordinates, group) ->
            val isExact = group.any { hit -> hit.names.any { it == query } }
            ClassMatch(
                coordinates = coordinates,
                matchedClassNames = group.flatMap { it.names }.distinct(),
                confidence = if (isExact) ClassMatch.Confidence.EXACT else ClassMatch.Confidence.RELOCATED,
                matchedVersion = group.maxByOrNull { it.timestamp }?.version,
            )
        }
    }

    private fun root(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun docs(root: JsonObject): JsonArray =
        root.objectOrNull("response")?.arrayOrNull("docs") ?: JsonArray()
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** Solr 高亮片段形如 `<em>com</em>.<em>google</em>...`，去掉标记后才是真实类名。 */
private fun String.stripEmTags(): String = replace("<em>", "").replace("</em>", "")
