package dev.felix.mavensearch.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.felix.mavensearch.model.ArtifactVersion
import javax.swing.JList

internal class VersionCellRenderer : ColoredListCellRenderer<ArtifactVersion>() {
    override fun customizeCellRenderer(
        list: JList<out ArtifactVersion>,
        value: ArtifactVersion,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.version, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        when {
            value.isRelease -> append("  release", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            value.isLatest -> append("  latest", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
