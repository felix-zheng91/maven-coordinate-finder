package dev.felix.mavensearch.net

import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection

/**
 * 走 [HttpRequests] 而不是 java.net.http.HttpClient：它会自动应用 IDE 的代理与证书设置，
 * 这对以后接公司内网 Nexus 是刚需。
 */
class IntellijHttpFetcher : HttpFetcher {

    override suspend fun get(url: String, accept: String, timeoutMs: Int): HttpResult =
        withContext(Dispatchers.IO) {
            try {
                HttpRequests.request(url)
                    .accept(accept)
                    .connectTimeout(timeoutMs)
                    .readTimeout(timeoutMs)
                    .productNameAsUserAgent()
                    .throwStatusCodeException(false)
                    .connect { request ->
                        val code = (request.connection as HttpURLConnection).responseCode
                        HttpResult(code, if (code in 200..299) request.readString() else "")
                    }
            } catch (e: IOException) {
                throw HttpException(e.message ?: "network error", cause = e)
            }
        }

    override suspend fun getSuffix(url: String, suffixBytes: Int, timeoutMs: Int): BinaryRange =
        requestRange(url, "bytes=-$suffixBytes", timeoutMs)

    override suspend fun getRange(url: String, start: Long, end: Long, timeoutMs: Int): BinaryRange =
        requestRange(url, "bytes=$start-$end", timeoutMs)

    private suspend fun requestRange(url: String, range: String, timeoutMs: Int): BinaryRange =
        withContext(Dispatchers.IO) {
            try {
                HttpRequests.request(url)
                    .accept("application/java-archive")
                    .connectTimeout(timeoutMs)
                    .readTimeout(timeoutMs)
                    .productNameAsUserAgent()
                    .throwStatusCodeException(false)
                    .tuner { connection ->
                        connection.setRequestProperty("Range", range)
                    }
                    .connect { request ->
                        val connection = request.connection as HttpURLConnection
                        val code = connection.responseCode
                        val bytes = if (code in 200..299) request.inputStream.use { it.readBytes() } else ByteArray(0)
                        toBinaryRange(code, bytes, connection.getHeaderField("Content-Range"))
                    }
            } catch (e: IOException) {
                throw HttpException(e.message ?: "network error", cause = e)
            }
        }

    private fun toBinaryRange(code: Int, bytes: ByteArray, contentRange: String?): BinaryRange {
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            val parsed = parseContentRange(contentRange)
            if (parsed != null) {
                return BinaryRange(bytes, parsed.first, parsed.second, code)
            }
            // 206 但拿不到 Content-Range：无法确认绝对偏移，按保守方式处理
            return BinaryRange(bytes, 0, bytes.size.toLong(), code)
        }
        // 200 表示服务器忽略了 Range，返回的是完整资源
        return BinaryRange(bytes, 0, bytes.size.toLong(), code)
    }

    /** 解析 `bytes 100-199/1234`，返回 (起始偏移, 总大小)。 */
    private fun parseContentRange(value: String?): Pair<Long, Long>? {
        if (value == null) return null
        val match = CONTENT_RANGE.find(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return start to total
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
    }
}
