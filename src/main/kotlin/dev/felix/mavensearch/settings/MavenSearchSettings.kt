package dev.felix.mavensearch.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.felix.mavensearch.model.DependencyFormat
import dev.felix.mavensearch.net.HttpFetcher
import dev.felix.mavensearch.service.MavenSearchService

@Service(Service.Level.APP)
@State(
    name = "MavenSearchSettings",
    storages = [Storage("maven-search.xml")],
)
class MavenSearchSettings : PersistentStateComponent<MavenSearchSettings.State> {

    /**
     * 每个字段都必须有默认值且是可写的 var —— XmlSerializer 依赖无参构造与 setter。
     */
    class State {
        var sourceId: String = MavenSearchService.SOURCE_CENTRAL
        var pageSize: Int = 50
        var requestTimeoutMs: Int = HttpFetcher.DEFAULT_TIMEOUT_MS
        var debounceMs: Int = 250
        var defaultFormat: String = DependencyFormat.MAVEN_XML.name

        /** 类名反查时是否展示 shade/repackaged 的近似结果，默认 false（准确性优先）。 */
        var showRelocatedClassMatches: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): MavenSearchSettings = service()
    }
}
