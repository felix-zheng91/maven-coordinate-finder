package dev.felix.mavensearch.service

import java.util.concurrent.ConcurrentHashMap

/** 简单的 TTL + 容量淘汰缓存。key 以 source id 开头，切换数据源时可按前缀清理。 */
class SearchCache(
    private val maxEntries: Int = 256,
    private val ttlMs: Long = 5 * 60_000,
) {
    private data class Entry(val value: Any, val createdAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrLoad(key: String, loader: suspend () -> T): T {
        val now = System.currentTimeMillis()
        map[key]?.let { entry ->
            if (now - entry.createdAt < ttlMs) return entry.value as T
            map.remove(key)
        }

        val value = loader()
        if (map.size >= maxEntries) evictExpired(now)
        map[key] = Entry(value, now)
        return value
    }

    fun invalidateSource(sourceId: String) {
        map.keys.removeIf { it.startsWith("$sourceId|") }
    }

    private fun evictExpired(now: Long) {
        map.entries.removeIf { now - it.value.createdAt >= ttlMs }
        if (map.size >= maxEntries) {
            map.entries.sortedBy { it.value.createdAt }
                .take(map.size - maxEntries / 2)
                .forEach { map.remove(it.key) }
        }
    }
}
