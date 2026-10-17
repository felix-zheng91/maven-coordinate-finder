package dev.felix.mavensearch.source.central

import dev.felix.mavensearch.model.ArtifactCoordinates
import java.net.URLEncoder

object CentralUrls {
    /** 新版 Central 兼容端点：实测更快、更稳，且 latestVersion 索引更新。 */
    const val NEW_SOLR = "https://central.sonatype.com/solrsearch/select"

    /** Legacy 端点：索引更全，也是目前唯一还带类名（fc:）索引的地方。 */
    const val LEGACY_SOLR = "https://search.maven.org/solrsearch/select"

    const val REPO1 = "https://repo1.maven.org/maven2"

    fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * 把用户输入转成 Solr 查询串。
     * - `g:a` 形式 → `g:"g" AND a:"a"`
     * - 其余 → 转义后的裸关键词
     */
    fun keywordQuery(raw: String): String {
        val text = raw.trim()
        val colon = text.indexOf(':')
        if (colon > 0 && colon < text.length - 1 && !text.contains(' ')) {
            val groupId = text.substring(0, colon).trim()
            val artifactId = text.substring(colon + 1).trim()
            if (groupId.isNotEmpty() && artifactId.isNotEmpty()) {
                return "g:\"$groupId\" AND a:\"$artifactId\""
            }
        }
        return escapeLucene(text)
    }

    fun versionListUrl(coordinates: ArtifactCoordinates): String =
        "$REPO1/${coordinates.directoryPath}/maven-metadata.xml"

    /**
     * Lucene 保留字符必须转义，否则用户输入 `spring:boot` 之类会直接 400。
     * 每个 `&`/`|` 单独转义，顺带覆盖 `&&`、`||`。
     */
    private const val LUCENE_SPECIAL = "+-&|!(){}[]^\"~*?:\\/"

    fun escapeLucene(value: String): String = buildString {
        for (ch in value) {
            if (ch in LUCENE_SPECIAL) append('\\')
            append(ch)
        }
    }
}
