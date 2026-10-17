package dev.felix.mavensearch.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MavenSearchToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MavenSearchPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false).apply {
            isCloseable = false
            setDisposer(panel)
        }
        toolWindow.contentManager.addContent(content)
        panel.requestFocus()
    }
}
