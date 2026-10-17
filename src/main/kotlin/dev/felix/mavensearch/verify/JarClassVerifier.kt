package dev.felix.mavensearch.verify

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.source.central.CentralUrls
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 类名反查的独立验证器：直接从 repo1 读取 jar 的中央目录，确认某个 `.class` 真实存在。
 *
 * 这条路径不依赖任何搜索索引，因此即使搜索 API 出问题，对已经拿到的候选 artifact
 * 仍然可以给出确定性的答案。最坏情况下只下载约 64KB + 中央目录。
 */
class JarClassVerifier(
    private val http: HttpFetcher,
    private val timeoutMs: () -> Int = { HttpFetcher.DEFAULT_TIMEOUT_MS },
) : ClassVerifier {
    private val cache = ConcurrentHashMap<String, Boolean>()

    override suspend fun contains(
        coordinates: ArtifactCoordinates,
        version: String,
        fqcn: String,
    ): Boolean {
        val classPath = fqcn.replace('.', '/') + ".class"
        val key = "${coordinates.id}:$version|$classPath"
        cache[key]?.let { return it }

        val result = try {
            probe(jarUrl(coordinates, version), classPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        cache[key] = result
        return result
    }

    fun cachedResult(coordinates: ArtifactCoordinates, version: String, fqcn: String): Boolean? =
        cache["${coordinates.id}:$version|${fqcn.replace('.', '/')}.class"]

    private suspend fun probe(url: String, classPath: String): Boolean {
        val tail = http.getSuffix(url, TAIL_BYTES, timeoutMs())
        if (tail.statusCode !in 200..299) return false

        val location = ZipCentralDirectory.locate(tail.bytes, tail.startOffset, tail.totalSize)
            ?: return false

        val tailEnd = tail.startOffset + tail.bytes.size
        val cdBytes = if (location.offset >= tail.startOffset &&
            location.offset + location.size <= tailEnd
        ) {
            val from = (location.offset - tail.startOffset).toInt()
            tail.bytes.copyOfRange(from, from + location.size.toInt())
        } else {
            val range = http.getRange(
                url,
                location.offset,
                location.offset + location.size - 1,
                timeoutMs(),
            )
            if (range.statusCode !in 200..299) return false
            range.bytes
        }

        return ZipCentralDirectory.containsEntry(cdBytes, classPath)
    }

    private fun jarUrl(coordinates: ArtifactCoordinates, version: String): String =
        "${CentralUrls.REPO1}/${coordinates.directoryPath}/$version/" +
            "${coordinates.artifactId}-$version.jar"

    private companion object {
        const val TAIL_BYTES = 64 * 1024
    }
}
