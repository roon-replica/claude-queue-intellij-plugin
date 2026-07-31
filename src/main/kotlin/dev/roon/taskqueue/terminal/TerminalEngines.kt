package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 어느 엔진의 탭이든 [TerminalHandle] 로 바꿔주는 곳. **엔진을 아는 유일한 지점이다.**
 *
 * 신(Reworked) 엔진 지원을 붙일 때 여기에만 분기를 추가한다 —
 * 그 API 는 `@ApiStatus.Experimental` 이라 깨질 수 있으므로 구현 클래스도 따로 두고,
 * 구버전 IDE 에서는 그 클래스를 아예 로드하지 않을 수 있게 접근을 이 한 곳으로 모은다.
 */
object TerminalEngines {

    /** 이 Content 를 다룰 수 있으면 핸들을, 지원하지 않는 엔진이면 null */
    fun handleFor(content: Content): TerminalHandle? {
        val widget = runCatching { TerminalToolWindowManager.getWidgetByContent(content) }.getOrNull()
        return widget?.let(::ClassicTerminalHandle)
    }

    /**
     * 새 탭을 만든다.
     *
     * `requestFocus=false` — 자동 진행이 사용자 화면을 낚아채지 않게. 대신 부른 쪽에서
     * 탭을 보이게만 한다(터미널은 탭이 보일 때까지 세션 시작을 미룬다).
     */
    fun createTab(project: Project, cwd: String, label: String): TerminalHandle? = runCatching {
        val widget: ShellTerminalWidget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(cwd, label, false)
        ClassicTerminalHandle(widget)
    }.getOrNull()
}
