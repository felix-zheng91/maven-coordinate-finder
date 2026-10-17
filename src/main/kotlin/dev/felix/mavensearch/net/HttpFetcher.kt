package dev.felix.mavensearch.net

data class HttpResult(val statusCode: Int, val body: String) {
    fun requireSuccess(): HttpResult =
        if (statusCode in 200..299) this else throw HttpException("HTTP $statusCode", statusCode)
}

/**
 * 二进制分段响应。[startOffset] 是 [bytes] 在完整资源中的绝对起始位置，
 * [totalSize] 是完整资源大小（服务器忽略 Range 时等于 bytes.size）。
 */
data class BinaryRange(
    val bytes: ByteArray,
    val startOffset: Long,
    val totalSize: Long,
    val statusCode: Int,
)

class HttpException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 网络访问的注入点。生产实现走 IDE 的 [com.intellij.util.io.HttpRequests]（自动继承代理设置），
 * 单元测试塞一个假实现即可完全离线。
 */
interface HttpFetcher {
    suspend fun get(
        url: String,
        accept: String = "application/json",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): HttpResult

    /** 读取资源末尾的 [suffixBytes] 字节。用于在不下载整个 jar 的前提下定位 ZIP 中央目录。 */
    suspend fun getSuffix(
        url: String,
        suffixBytes: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): BinaryRange

    /** 读取 `[start, end]` 闭区间字节。 */
    suspend fun getRange(
        url: String,
        start: Long,
        end: Long,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): BinaryRange

    companion object {
        /**
         * 实测成功响应约 0.5-1.2s，失败时服务端会直接挂住。
         * 超时设短一些，才能尽快触发端点回退，而不是让用户干等。
         */
        const val DEFAULT_TIMEOUT_MS = 4_000
    }
}
