package dev.felix.mavensearch.search

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactSearchResult
import dev.felix.mavensearch.model.ClassMatch
import dev.felix.mavensearch.verify.ClassVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 类名索引不可用时的降级搜索。
 *
 * 思路：用简单类名在稳定端点上找候选 artifact，然后逐个读取 jar 中央目录，
 * 只有 jar 里真的存在这个 `.class` 才返回。召回率不高（简单类名不一定出现在
 * artifact 名称里），但**每一条结果都是经过 jar 内容验证的**，不会给错坐标。
 */
class ClassFallbackSearch(
    private val keywordSearch: suspend (query: String, limit: Int) -> List<ArtifactSearchResult>,
    private val releaseResolver: suspend (coordinates: ArtifactCoordinates) -> String?,
    private val verifier: ClassVerifier,
) {
    suspend fun search(fqcn: String, limit: Int): List<ClassMatch> = coroutineScope {
        val simpleName = fqcn.substringAfterLast('.').substringBefore('$')
        if (simpleName.isBlank()) return@coroutineScope emptyList()

        val candidates = try {
            keywordSearch(simpleName, MAX_CANDIDATES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@coroutineScope emptyList()
        }

        val semaphore = Semaphore(MAX_PARALLEL)
        candidates.map { candidate ->
            async {
                semaphore.withPermit {
                    val version = candidate.latestVersion
                        ?: releaseResolver(candidate.coordinates)
                        ?: return@withPermit null
                    if (!verifier.contains(candidate.coordinates, version, fqcn)) {
                        return@withPermit null
                    }
                    ClassMatch(
                        coordinates = candidate.coordinates,
                        matchedClassNames = listOf(fqcn),
                        confidence = ClassMatch.Confidence.EXACT,
                        matchedVersion = version,
                    )
                }
            }
        }.awaitAll().filterNotNull().take(limit)
    }

    private companion object {
        const val MAX_CANDIDATES = 20
        const val MAX_PARALLEL = 6
    }
}
