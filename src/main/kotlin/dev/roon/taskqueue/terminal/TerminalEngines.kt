package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 어느 엔진의 탭이든 [TerminalHandle] 로 바꿔주는 곳. **엔진을 아는 유일한 지점이다.**
 *
 * 신(Reworked) 엔진 API 는 `@ApiStatus.Experimental` 이고 **구버전 IDE 에는 클래스 자체가
 * 없다**(2024.3 확인). 그래서 [reworkedAvailable] 로 존재를 먼저 확인한 뒤에만
 * [ReworkedTerminalHandle] 을 건드린다 — 확인 전에 그 타입이 이 파일 밖으로 새면
 * 구버전에서 클래스 로딩이 터진다.
 */
object TerminalEngines {

    /**
     * 신 엔진 클래스가 이 IDE 에 있는지. 없으면 [ReworkedTerminalHandle] 을 **로드조차 하지 않는다**
     * — JVM 은 클래스를 처음 쓸 때 로드하므로, 이 가드가 구버전 호환의 전부다.
     */
    private val reworkedAvailable: Boolean by lazy {
        runCatching {
            Class.forName(
                "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager",
                false,
                TerminalEngines::class.java.classLoader,
            )
        }.isSuccess
    }

    /** 이 Content 를 다룰 수 있으면 핸들을, 지원하지 않는 엔진이면 null */
    fun handleFor(project: Project, content: Content): TerminalHandle? {
        val classic = runCatching { TerminalToolWindowManager.getWidgetByContent(content) }.getOrNull()
        if (classic != null) return ClassicTerminalHandle(classic)
        if (!reworkedAvailable) return null
        return ReworkedTerminalTabs.handleFor(project, content)
    }

    /**
     * 새 탭을 만든다.
     *
     * 포커스는 뺏지 않는다 — 자동 진행이 사용자 화면을 낚아채지 않게.
     * 엔진은 IDE 설정을 따른다: 사용자가 Reworked 를 쓰면 구 API 로 만든 탭은
     * 나중에 다시 찾을 수 없으므로, 신 엔진이 있으면 그쪽으로 만든다.
     */
    fun createTab(project: Project, cwd: String, label: String): TerminalHandle? {
        if (reworkedAvailable) {
            ReworkedTerminalTabs.createTab(project, cwd, label)?.let { return it }
        }
        return runCatching {
            val widget: ShellTerminalWidget = TerminalToolWindowManager.getInstance(project)
                .createLocalShellWidget(cwd, label, false)
            ClassicTerminalHandle(widget)
        }.getOrNull()
    }
}
