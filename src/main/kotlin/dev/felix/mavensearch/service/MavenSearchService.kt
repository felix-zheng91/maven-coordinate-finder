package dev.felix.mavensearch.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ArtifactVersionList
import dev.felix.mavensearch.model.ClassSearchOutcome
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.net.IntellijHttpFetcher
import dev.felix.mavensearch.search.ClassFallbackSearch
import dev.felix.mavensearch.settings.MavenSearchSettings
import dev.felix.mavensearch.source.ArtifactSearchSource
import dev.felix.mavensearch.source.ClassIndexUnavailableException
import dev.felix.mavensearch.source.MavenCentralSource
import dev.felix.mavensearch.source.SourceCapability
import dev.felix.mavensearch.source.central.CentralUrls
import dev.felix.mavensearch.source.supports
import dev.felix.mavensearch.verify.JarClassVerifier

/**
 * application 级服务：搜索结果与项目无关，缓存应当跨项目共享。
 */
@Service(Service.Level.APP)
class MavenSearchService : Disposable {

    private val http: HttpFetcher = IntellijHttpFetcher()
    private val cache = SearchCache()
    private val jarVerifier = JarClassVerifier(http)

    private val sources: Map<String, ArtifactSearchSource> = listOf(
        MavenCentralSource(
            http = http,
            // 默认源：legacy 排序更好，失败时回退到更快的新端点
            primaryEndpoint = CentralUrls.LEGACY_SOLR,
            fallbackEndpoint = CentralUrls.NEW_SOLR,
            id = SOURCE_CENTRAL,
            displayName = "Maven Central",
            timeoutMs = { MavenSearchSettings.getInstance().state.requestTimeoutMs },
        ),
        MavenCentralSource(
            http = http,
            // 备用源：新端点优先（更快），失败时回退到 legacy
            primaryEndpoint = CentralUrls.NEW_SOLR,
            fallbackEndpoint = CentralUrls.LEGACY_SOLR,
            id = SOURCE_CENTRAL_FAST,
            displayName = "Maven Central (Fast)",
            timeoutMs = { MavenSearchSettings.getInstance().state.requestTimeoutMs },
        ),
    ).associateBy { it.id }

    /** 类名索引失效时的降级搜索：新端点找候选 + jar 内容校验。 */
    private val classFallback = ClassFallbackSearch(
        keywordSearch = { query, limit -> stableSource.searchArtifacts(query, limit) },
        releaseResolver = { coordinates ->
            runCatching { stableSource.listVersions(coordinates).release }.getOrNull()
        },
        verifier = jarVerifier,
    )

    /** 新端点优先的源，不依赖 legacy 索引。 */
    private val stableSource: ArtifactSearchSource
        get() = sources.getValue(SOURCE_CENTRAL_FAST)

    val availableSources: List<ArtifactSearchSource>
        get() = sources.values.toList()

    /** 设置里的 id 失效时回退到主数据源。 */
    val currentSource: ArtifactSearchSource
        get() = sources[MavenSearchSettings.getInstance().state.sourceId]
            ?: sources.getValue(SOURCE_CENTRAL)

    suspend fun searchArtifacts(query: String, limit: Int): List<ArtifactSearchResult> {
        val source = currentSource
        return cache.getOrLoad("${source.id}|kw|$query|$limit") {
            source.searchArtifacts(query, limit)
        }
    }

    suspend fun listVersions(coordinates: ArtifactCoordinates): ArtifactVersionList {
        val source = currentSource
        return cache.getOrLoad("${source.id}|ver|${coordinates.id}") {
            source.listVersions(coordinates)
        }
    }

    suspend fun searchByClassName(fqcn: String, limit: Int): ClassSearchOutcome {
        val source = currentSource
        check(source.supports(SourceCapability.CLASS_SEARCH)) {
            "${source.displayName} does not support class name search"
        }

        return try {
            val matches = cache.getOrLoad("${source.id}|cls|$fqcn|$limit") {
                source.searchByClassName(fqcn, limit)
            }
            ClassSearchOutcome(matches, degraded = false)
        } catch (e: ClassIndexUnavailableException) {
            // 索引失效：走独立于索引的 jar 校验路径
            val matches = cache.getOrLoad("fallback|cls|$fqcn|$limit") {
                classFallback.search(fqcn, limit)
            }
            ClassSearchOutcome(matches, degraded = true)
        }
    }

    /**
     * 独立校验：读取 jar 中央目录，确认 [fqcn] 是否真实存在。
     * 不依赖任何搜索索引；[version] 为空时用该 artifact 的 release 版本。
     */
    suspend fun verifyClass(
        coordinates: ArtifactCoordinates,
        version: String?,
        fqcn: String,
    ): Boolean {
        val resolved = version ?: runCatching {
            stableSource.listVersions(coordinates).release
        }.getOrNull() ?: return false
        return jarVerifier.contains(coordinates, resolved, fqcn)
    }

    override fun dispose() = Unit

    companion object {
        const val SOURCE_CENTRAL = "maven-central"
        const val SOURCE_CENTRAL_FAST = "maven-central-fast"

        fun getInstance(): MavenSearchService = service()
    }
}
