package dev.felix.mavensearch.ui

import com.intellij.ui.SimpleListCellRenderer
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.source.ArtifactSearchSource
import javax.swing.JList

/** artifact 列表里的一行。关键词结果和类名反查结果共用同一套渲染。 */
internal data class ArtifactRow(
    val coordinates: ArtifactCoordinates,
    val hint: String? = null,
    val matchedClassName: String? = null,
    val relocated: Boolean = false,
    /** 类名反查时用于 jar 校验的版本。 */
    val verifyVersion: String? = null,
    /** null = 未校验/进行中，true = jar 中确实存在该类。 */
    val verified: Boolean? = null,
)

/** 数据源下拉框的条目。 */
internal data class SourceItem(val source: ArtifactSearchSource) {
    val displayName: String get() = source.displayName
}

/** 下拉框渲染器。`SimpleListCellRenderer.create` 在新版本已弃用，直接用匿名子类。 */
internal fun <T> listCellRenderer(toText: (T?) -> String): SimpleListCellRenderer<T> =
    object : SimpleListCellRenderer<T>() {
        override fun customize(
            list: JList<out T>,
            value: T?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            text = toText(value)
        }
    }
