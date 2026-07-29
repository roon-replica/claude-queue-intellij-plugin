package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** 작업이 도는 터미널 탭으로 포커스를 옮긴다 */
object TerminalTabFocuser {

    /** @return 옮겼는지 여부 (탭이 없으면 false) */
    fun focus(project: Project, tabLabel: String): Boolean {
        if (tabLabel.isEmpty()) return false
        val widget = TerminalSessionRegistry.getInstance().find(tabLabel)?.widget ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return false

        toolWindow.activate({
            // 탭 선택까지 해야 화면에 보인다 — 포커스만으론 다른 탭이 앞에 남는다
            selectContent(project, widget)
            runCatching { widget.requestFocus() }
        }, true, true)
        return true
    }

    private fun selectContent(project: Project, widget: ShellTerminalWidget) {
        val manager = ToolWindowManager.getInstance(project)
            .getToolWindow("Terminal")?.contentManager ?: return
        val content = manager.contents.firstOrNull {
            runCatching { TerminalToolWindowManager.getWidgetByContent(it) === widget }.getOrDefault(false)
        } ?: return
        manager.setSelectedContent(content, true)
    }
}
