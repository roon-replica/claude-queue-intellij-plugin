package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** 작업이 도는 터미널 탭을 보여준다 */
object TerminalTabFocuser {

    /**
     * @param moveFocus true 면 키보드 포커스까지 터미널로 옮긴다(개입용).
     *                  false 면 탭만 앞으로 가져오고 포커스는 그대로 둔다(확인용).
     * @return 찾아서 보여줬는지 여부
     */
    fun focus(project: Project, tabLabel: String, moveFocus: Boolean): Boolean {
        if (tabLabel.isEmpty()) return false
        val tab = TerminalSessionRegistry.getInstance().find(tabLabel) ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL) ?: return false

        // 탭 선택을 activate 보다 먼저 한다 — activate 의 포커스 처리가 이전 탭을 다시 앞세운다
        selectContent(toolWindow, tab.widget)
        toolWindow.activate({
            selectContent(toolWindow, tab.widget)
            if (moveFocus) tab.focus()
        }, moveFocus, moveFocus)
        return true
    }

    private fun selectContent(toolWindow: ToolWindow, widget: JBTerminalWidget) {
        val manager = toolWindow.contentManager
        val content = manager.contents.firstOrNull { sameWidget(it, widget) } ?: return
        manager.setSelectedContent(content, false)
    }

    private fun sameWidget(content: Content, widget: JBTerminalWidget): Boolean = runCatching {
        TerminalToolWindowManager.getWidgetByContent(content) === widget
    }.getOrDefault(false)

    private const val TERMINAL = "Terminal"
}
