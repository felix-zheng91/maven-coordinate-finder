package dev.felix.mavensearch

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 在内存里构造 ZIP，用于测试中央目录解析，不依赖任何外部文件。 */
object TestJar {

    fun create(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 生成条目足够多的 jar，使中央目录大到无法被 64KB 的尾部窗口覆盖。 */
    fun manyEntries(count: Int): ByteArray =
        create((0 until count).associate { "com/example/Entry$it.class" to "" })
}
