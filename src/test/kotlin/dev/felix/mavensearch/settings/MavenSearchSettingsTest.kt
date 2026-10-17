package dev.felix.mavensearch.settings

import com.intellij.util.xmlb.XmlSerializer
import dev.felix.mavensearch.service.MavenSearchService
import org.junit.Assert.assertEquals
import org.junit.Test

class MavenSearchSettingsTest {

    @Test
    fun `state survives an xml round trip`() {
        val state = MavenSearchSettings.State().apply {
            sourceId = MavenSearchService.SOURCE_CENTRAL_FAST
            pageSize = 77
            requestTimeoutMs = 4_000
            debounceMs = 120
            defaultFormat = "GRADLE_KOTLIN"
            showRelocatedClassMatches = true
        }

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, MavenSearchSettings.State::class.java)

        assertEquals(MavenSearchService.SOURCE_CENTRAL_FAST, restored.sourceId)
        assertEquals(77, restored.pageSize)
        assertEquals(4_000, restored.requestTimeoutMs)
        assertEquals(120, restored.debounceMs)
        assertEquals("GRADLE_KOTLIN", restored.defaultFormat)
        assertEquals(true, restored.showRelocatedClassMatches)
    }
}
