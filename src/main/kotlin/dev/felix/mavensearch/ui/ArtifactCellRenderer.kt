package dev.felix.mavensearch.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

internal class ArtifactCellRenderer : ColoredListCellRenderer<ArtifactRow>() {
    override fun customizeCellRenderer(
        list: JList<out ArtifactRow>,
        value: ArtifactRow,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.coordinates.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.coordinates.groupId}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        value.hint?.takeIf { it.isNotBlank() }?.let {
            append("  ·  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        value.matchedClassName?.let {
            // 展示"凭什么说这个坐标是对的"，这是类名反查的准确性契约
            append("  ·  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        if (value.verified == true) {
            // 通过读取 jar 中央目录独立确认过，不依赖搜索索引
            append("  ✓", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
