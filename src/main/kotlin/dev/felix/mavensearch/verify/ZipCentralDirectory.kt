package dev.felix.mavensearch.verify

/**
 * 解析 ZIP 中央目录。
 *
 * 用于只读取 jar 末尾几十 KB + 中央目录，就能判断某个 `.class` 是否存在，
 * 完全不需要下载整个 jar。这是类名反查在索引之外的独立验证手段。
 */
object ZipCentralDirectory {

    private const val EOCD_SIG = 0x06054b50L
    private const val EOCD64_LOCATOR_SIG = 0x07064b50L
    private const val EOCD64_SIG = 0x06064b50L
    private const val CD_ENTRY_SIG = 0x02014b50L

    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT = 65535
    private const val U32_MAX = 0xFFFFFFFFL

    data class Location(val offset: Long, val size: Long)

    /**
     * 在文件尾部字节中定位中央目录。
     *
     * @param tail 文件末尾若干字节
     * @param tailStart [tail] 第一个字节在完整文件中的绝对偏移
     * @param totalSize 完整文件大小
     * @return 中央目录的绝对偏移与长度；无法可信定位时返回 null
     */
    fun locate(tail: ByteArray, tailStart: Long, totalSize: Long): Location? {
        if (tail.size < EOCD_MIN_SIZE) return null

        val eocd = findEocd(tail) ?: return null
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)

        if (cdSize == U32_MAX || cdOffset == U32_MAX) {
            val zip64 = readZip64(tail, eocd, tailStart) ?: return null
            cdOffset = zip64.first
            cdSize = zip64.second
        }

        if (cdSize <= 0 || cdOffset < 0) return null
        if (totalSize > 0 && cdOffset + cdSize > totalSize) return null

        return Location(cdOffset, cdSize)
    }

    /** 解析中央目录中的条目名（ZIP 内部路径，如 `com/google/common/collect/Lists.class`）。 */
    fun entryNames(cd: ByteArray): List<String> {
        val names = ArrayList<String>()
        var index = 0
        while (index + 46 <= cd.size && u32(cd, index) == CD_ENTRY_SIG) {
            val nameLength = u16(cd, index + 28)
            val extraLength = u16(cd, index + 30)
            val commentLength = u16(cd, index + 32)
            val nameStart = index + 46
            if (nameStart + nameLength > cd.size) break

            names += String(cd, nameStart, nameLength, Charsets.UTF_8)
            index = nameStart + nameLength + extraLength + commentLength
        }
        return names
    }

    fun containsEntry(cd: ByteArray, entryName: String): Boolean {
        var index = 0
        while (index + 46 <= cd.size && u32(cd, index) == CD_ENTRY_SIG) {
            val nameLength = u16(cd, index + 28)
            val extraLength = u16(cd, index + 30)
            val commentLength = u16(cd, index + 32)
            val nameStart = index + 46
            if (nameStart + nameLength > cd.size) return false

            if (nameLength == entryName.length &&
                String(cd, nameStart, nameLength, Charsets.UTF_8) == entryName
            ) {
                return true
            }
            index = nameStart + nameLength + extraLength + commentLength
        }
        return false
    }

    private fun findEocd(tail: ByteArray): Int? {
        val earliest = maxOf(0, tail.size - EOCD_MIN_SIZE - MAX_COMMENT)
        for (index in tail.size - EOCD_MIN_SIZE downTo earliest) {
            if (u32(tail, index) != EOCD_SIG) continue
            val commentLength = u16(tail, index + 20)
            if (index + EOCD_MIN_SIZE + commentLength <= tail.size) return index
        }
        return null
    }

    /** ZIP64：EOCD 前面 20 字节是 locator，指向 EOCD64 记录。 */
    private fun readZip64(tail: ByteArray, eocd: Int, tailStart: Long): Pair<Long, Long>? {
        val locator = eocd - 20
        if (locator < 0 || u32(tail, locator) != EOCD64_LOCATOR_SIG) return null

        val eocd64Absolute = u64(tail, locator + 8)
        val index = eocd64Absolute - tailStart
        if (index < 0 || index + 56 > tail.size) return null
        val i = index.toInt()
        if (u32(tail, i) != EOCD64_SIG) return null

        val cdSize = u64(tail, i + 40)
        val cdOffset = u64(tail, i + 48)
        return cdOffset to cdSize
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, index: Int): Long =
        (bytes[index].toLong() and 0xFF) or
            ((bytes[index + 1].toLong() and 0xFF) shl 8) or
            ((bytes[index + 2].toLong() and 0xFF) shl 16) or
            ((bytes[index + 3].toLong() and 0xFF) shl 24)

    private fun u64(bytes: ByteArray, index: Int): Long {
        var value = 0L
        for (offset in 0 until 8) {
            value = value or ((bytes[index + offset].toLong() and 0xFF) shl (8 * offset))
        }
        return value
    }
}
