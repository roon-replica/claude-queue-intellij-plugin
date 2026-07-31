package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.Content

/**
 * 신(Reworked) 엔진의 탭을 찾고 만드는 곳.
 *
 * [TerminalEngines] 가 클래스 존재를 확인한 뒤에만 이 오브젝트를 건드린다 —
 * 구버전 IDE 에는 여기서 쓰는 클래스가 없어서, 미리 로드되면 터진다.
 */
internal object ReworkedTerminalTabs {

    fun handleFor(project: Project, content: Content): TerminalHandle? = runCatching {
        tabsOf(project).firstOrNull { it.content === content }?.let(::ReworkedTerminalHandle)
    }.getOrNull()

    /**
     * 새 탭. `deferSessionStartUntilUiShown(false)` 로 **보이지 않아도 세션이 시작되게** 한다 —
     * 구 엔진에서는 탭을 보여줘야만 시작돼서 화면을 건드려야 했다.
     */
    fun createTab(project: Project, cwd: String, label: String): TerminalHandle? = runCatching {
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .workingDirectory(cwd)
            .tabName(label)
            .requestFocus(false)
            .deferSessionStartUntilUiShown(false)
            .createTab()
        ReworkedTerminalHandle(tab)
    }.getOrNull()

    private fun tabsOf(project: Project) =
        TerminalToolWindowTabsManager.getInstance(project).tabs
}
