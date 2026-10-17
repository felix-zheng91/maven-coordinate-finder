package dev.felix.mavensearch.source

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ArtifactVersionList
import dev.felix.mavensearch.model.ClassMatch

interface ArtifactSearchSource {
    val id: String
    val displayName: String
    val capabilities: Set<SourceCapability>

    suspend fun searchArtifacts(query: String, limit: Int, offset: Int = 0): List<ArtifactSearchResult>

    suspend fun listVersions(coordinates: ArtifactCoordinates): ArtifactVersionList

    /**
     * 按完整类名反查。实现必须保证 [ClassMatch.matchedClassNames] 是数据源真实报告的
     * 匹配类名，不允许猜测。
     */
    suspend fun searchByClassName(fqcn: String, limit: Int): List<ClassMatch>
}

fun ArtifactSearchSource.supports(capability: SourceCapability): Boolean =
    capability in capabilities
