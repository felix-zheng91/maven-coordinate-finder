package dev.felix.mavensearch.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 面板初始化涉及 service 查找、资源包、Swing 组件装配和 init 顺序，
 * 任何一处写错都只会在 runtime 炸。这里用真实平台环境把构造过程跑一遍。
 */
class MavenSearchPanelTest : BasePlatformTestCase() {

    fun testPanelCanBeCreatedAndDisposed() {
        val panel = MavenSearchPanel(project)
        try {
            assertNotNull(panel.component)
        } finally {
            panel.dispose()
        }
    }
}
