package dev.felix.mavensearch.search

import dev.felix.mavensearch.model.ArtifactCoordinates

sealed interface QueryIntent {
    data class Coordinates(val value: ArtifactCoordinates) : QueryIntent
    data class Keyword(val keyword: String) : QueryIntent
    data class ClassName(val fqcn: String) : QueryIntent
}

enum class SearchMode { AUTO, KEYWORD, CLASS }

/**
 * 判定用户到底想搜什么。规则刻意保守：宁可判成关键词，也不要把普通输入误判成类名。
 */
object QueryClassifier {

    private const val IDENT = "[A-Za-z_$][A-Za-z0-9_$]*"

    /** 至少 a.b.C 三个段，且末段首字母大写。 */
    private val FQCN = Regex("^($IDENT\\.){2,}$IDENT$")

    /** groupId:artifactId，两边都不允许出现空格。 */
    private val COORDINATES = Regex("^([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)$")

    fun classify(raw: String): QueryIntent {
        val text = raw.trim()
        if (text.isEmpty()) return QueryIntent.Keyword("")

        COORDINATES.matchEntire(text)?.let { match ->
            return QueryIntent.Coordinates(
                ArtifactCoordinates(match.groupValues[1], match.groupValues[2]),
            )
        }

        if (FQCN.matches(text) && text.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            return QueryIntent.ClassName(text)
        }

        return QueryIntent.Keyword(text)
    }
}
