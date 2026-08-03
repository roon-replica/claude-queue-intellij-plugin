package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content

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
        // **찾았는지를 그대로 돌려준다** — 등록만 남은 닫힌 탭에 true 를 주면 호출부가
        // "보냈다" 고 착각해 아무 일도 없이 끝난다. 방금 만든 탭은 Content 등록이 늦어
        // false 가 나올 수 있는데, 그 호출부들은 반환값을 쓰지 않는다.
        val found = selectContent(toolWindow, tab)
        toolWindow.activate({
            selectContent(toolWindow, tab)
            if (moveFocus) tab.focus()
        }, moveFocus, moveFocus)
        return found
    }

    /**
     * 그 탭이 **지금 터미널 툴윈도우에 실제로 열려 있는지.**
     *
     * [focus] 의 반환값으로는 알 수 없다 — 등록만 남아 있으면 true 를 준다.
     * 그리고 `TerminalHandle.alive` 도 세션 상태만 보므로 탭을 닫은 직후엔 아직
     * 살아있다고 답한다(실측). 닫힌 탭을 되살리려다 조용히 실패하는 걸 막는 판정이다.
     */
    fun isOpen(project: Project, tabLabel: String): Boolean {
        if (tabLabel.isEmpty()) return false
        val tab = TerminalSessionRegistry.getInstance().find(tabLabel) ?: return false
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL) ?: return false
        return toolWindow.contentManager.contents.any { sameTab(it, tab) }
    }

    /** @return 그 탭의 Content 를 찾아 선택했는지 */
    private fun selectContent(toolWindow: ToolWindow, tab: TerminalSessionRegistry.Tab): Boolean {
        val manager = toolWindow.contentManager
        val content = manager.contents.firstOrNull { sameTab(it, tab) } ?: return false
        manager.setSelectedContent(content, false)
        return true
    }

    private fun sameTab(content: Content, tab: TerminalSessionRegistry.Tab): Boolean =
        runCatching { tab.handle.matches(content) }.getOrDefault(false)

    private const val TERMINAL = "Terminal"
}
