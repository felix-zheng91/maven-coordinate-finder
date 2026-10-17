package dev.felix.mavensearch.model

/** Maven 坐标，不含版本。 */
data class ArtifactCoordinates(val groupId: String, val artifactId: String) {
    val id: String get() = "$groupId:$artifactId"

    /** groupId 转仓库路径：com.google.guava -> com/google/guava */
    val directoryPath: String get() = "${groupId.replace('.', '/')}/$artifactId"

    override fun toString(): String = id
}

/** 关键词搜索命中的一个 artifact。 */
data class ArtifactSearchResult(
    val coordinates: ArtifactCoordinates,
    val latestVersion: String? = null,
    val packaging: String? = null,
    val versionCount: Int? = null,
)

data class ArtifactVersion(
    val version: String,
    val isLatest: Boolean = false,
    val isRelease: Boolean = false,
)

data class ArtifactVersionList(
    val coordinates: ArtifactCoordinates,
    /** 约定：新版本在前。 */
    val versions: List<ArtifactVersion>,
    val latest: String? = null,
    val release: String? = null,
)

/**
 * 类名反查结果。
 *
 * [confidence] 是"准确性优先"的核心：只有索引明确报告"匹配到的类名与查询串完全相等"
 * 才算 [Confidence.EXACT]；带 repackaged./shade 前缀的副本一律降级为
 * [Confidence.RELOCATED]，默认不展示。
 */
data class ClassMatch(
    val coordinates: ArtifactCoordinates,
    val matchedClassNames: List<String>,
    val confidence: Confidence,
    val matchedVersion: String? = null,
) {
    enum class Confidence { EXACT, RELOCATED }
}
