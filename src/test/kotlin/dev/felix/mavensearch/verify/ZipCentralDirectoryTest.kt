package dev.felix.mavensearch.verify

import dev.felix.mavensearch.TestJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipCentralDirectoryTest {

    @Test
    fun `locates and parses the central directory from the tail`() {
        val bytes = TestJar.create(
            mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
                "com/example/Foo.class" to "cafebabe",
                "com/example/Bar.class" to "cafebabe",
            ),
        )
        val tailSize = minOf(64 * 1024, bytes.size)
        val tailStart = bytes.size - tailSize
        val tail = bytes.copyOfRange(tailStart, bytes.size)

        val location = ZipCentralDirectory.locate(tail, tailStart.toLong(), bytes.size.toLong())
        assertNotNull(location)

        val cd = bytes.copyOfRange(
            location!!.offset.toInt(),
            (location.offset + location.size).toInt(),
        )
        assertTrue(ZipCentralDirectory.containsEntry(cd, "com/example/Foo.class"))
        assertTrue(ZipCentralDirectory.containsEntry(cd, "com/example/Bar.class"))
        assertFalse(ZipCentralDirectory.containsEntry(cd, "com/example/Baz.class"))
        assertEquals(
            listOf("META-INF/MANIFEST.MF", "com/example/Foo.class", "com/example/Bar.class"),
            ZipCentralDirectory.entryNames(cd),
        )
    }

    @Test
    fun `rejects data without an end of central directory`() {
        assertNull(ZipCentralDirectory.locate(ByteArray(128), 0, 128))
    }

    @Test
    fun `rejects offsets beyond the declared file size`() {
        val bytes = TestJar.create(mapOf("a.txt" to "x"))
        assertNull(ZipCentralDirectory.locate(bytes, 0, totalSize = 10))
    }
}
