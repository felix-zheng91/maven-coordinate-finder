package dev.felix.mavensearch.source

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ArtifactVersionList
import dev.felix.mavensearch.model.ClassMatch
import dev.felix.mavensearch.net.HttpException
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.source.central.CentralJsonParser
import dev.felix.mavensearch.source.central.CentralUrls
import dev.felix.mavensearch.source.central.MavenMetadataParser
import kotlinx.coroutines.delay

/**
 * Maven Central 数据源。
 *
 * 两个端点分工不同，不能混用：
 * - 关键词搜索走 [keywordEndpoint]（新版端点更稳、latestVersion 更新）；
 * - 类名反查固定走 [CentralUrls.LEGACY_SOLR] —— 新版端点的 fc: 索引实测是坏的
 *   （numFound 恒为 10000 且结果无关）；
 * - 版本列表固定走 repo1 的 maven-metadata.xml（最权威）。
 */
class MavenCentralSource(
    private val http: HttpFetcher,
    private val primaryEndpoint: String,
    private val fallbackEndpoint: String,
    override val id: String,
    override val displayName: String,
    private val timeoutMs: () -> Int = { HttpFetcher.DEFAULT_TIMEOUT_MS },
) : ArtifactSearchSource {

    override val capabilities: Set<SourceCapability> = setOf(
        SourceCapability.KEYWORD_SEARCH,
        SourceCapability.CLASS_SEARCH,
        SourceCapability.VERSION_LIST,
    )

    override suspend fun searchArtifacts(
        query: String,
        limit: Int,
        offset: Int,
    ): List<ArtifactSearchResult> {
        val luceneQuery = CentralUrls.keywordQuery(query)
        val body = fetchWithFallback { endpoint ->
            "$endpoint?q=${CentralUrls.enc(luceneQuery)}&rows=$limit&start=$offset&wt=json"
        }
        return CentralJsonParser.parseArtifactSearch(body)
    }

    override suspend fun listVersions(coordinates: ArtifactCoordinates): ArtifactVersionList {
        val url = CentralUrls.versionListUrl(coordinates)
        val body = withRetry {
            http.get(url, accept = "application/xml", timeoutMs = timeoutMs()).requireSuccess().body
        }
        return MavenMetadataParser.parse(body, coordinates)
    }

    override suspend fun searchByClassName(fqcn: String, limit: Int): List<ClassMatch> {
        // hl=true 是硬性要求：没有 highlighting.fch 就无法验证匹配到的到底是不是这个类。
        // 只有 legacy 端点有可用的 fc: 索引，因此这里不能走 fallback。
        val url = "${CentralUrls.LEGACY_SOLR}?q=${CentralUrls.enc("fc:$fqcn")}" +
            "&rows=$limit&wt=json&hl=true&hl.fl=fch&hl.snippets=5"
        val body = try {
            withRetry { http.get(url, timeoutMs = timeoutMs()).requireSuccess().body }
        } catch (e: HttpException) {
            // 端点下线、被拦截或持续超时：交给上层走降级路径，而不是弹一个通用网络错误
            throw ClassIndexUnavailableException(
                "Maven Central class index unavailable: ${e.message}",
                e,
            )
        }
        return CentralJsonParser.parseClassSearch(body, fqcn)
    }

    /**
     * 先打主端点，失败再换备用端点。
     *
     * 实测：legacy 端点排序明显更好（搜 guava 第一条就是 com.google.guava:guava），
     * 但约 1/5 的请求会超时；新端点稳定但把 guava 排到第 9 位。两者互补。
     */
    private suspend fun fetchWithFallback(urlFor: (String) -> String): String {
        val endpoints = listOf(primaryEndpoint, fallbackEndpoint).distinct()
        var lastError: HttpException? = null
        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                return http.get(urlFor(endpoint), timeoutMs = timeoutMs()).requireSuccess().body
            } catch (e: HttpException) {
                lastError = e
                if (index < endpoints.lastIndex) delay(150)
            }
        }
        throw lastError ?: HttpException("no endpoint available")
    }

    /** repo1 偶发失败，单次退避重试能显著改善体感；4xx 不重试。 */
    private suspend fun <T> withRetry(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            if (e.statusCode != null && e.statusCode in 400..499) throw e
            delay(400)
            block()
        }
}
