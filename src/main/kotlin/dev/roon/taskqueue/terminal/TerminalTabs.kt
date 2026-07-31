package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 프로젝트에 열려 있는 터미널 탭 조회.
 *
 * 툴윈도의 Content 를 훑는다 — `getWidgetByContent` 는 Content 당 **같은 객체**를 돌려주므로
 * 우리가 등록해둔 위젯과 동일성 비교가 성립한다. (`asNewWidget()` 은 그렇지 않다)
 */
object TerminalTabs {

    class Choice(
        val widget: JBTerminalWidget,
        val title: String,
        /** 이미 등록된 탭이면 그 정보 — 없으면 고를 때 등록한다 */
        val registered: TerminalSessionRegistry.Tab?,
    ) {
        /**
         * claude 가 실제로 도는지 프로세스 트리로 본다 — 런처와 **같은 판별**을 써야
         * 팔레트 라벨과 실제 동작이 어긋나지 않는다.
         */
        private val claudeRunning: Boolean
            get() = runCatching {
                val connector = widget.ttyConnector ?: return false
                val shellProcess = (connector as? ProcessTtyConnector)?.process ?: return false
                shellProcess.toHandle().descendants().anyMatch { handle ->
                    handle.info().command().orElse("").contains("claude")
                }
            }.getOrDefault(false)

        private val busy: Boolean
            get() = runCatching { (widget as? ShellTerminalWidget)?.hasRunningCommands() == true }
                .getOrDefault(false)

        /** 팔레트에 보여줄 한 줄 — 고르면 뭐가 일어나는지 알 수 있게 */
        val display: String
            get() {
                val session = registered?.sessionId
                return when {
                    session != null -> "$title  (continue — session ${session.take(8)})"
                    claudeRunning -> "$title  (claude running — continue here)"
                    busy -> "$title  (busy — running something else)"
                    else -> "$title  (shell — claude will be started here)"
                }
            }
    }

    fun list(project: Project): List<Choice> {
        val registry = TerminalSessionRegistry.getInstance()
        return contents(project).mapNotNull { content ->
            val widget = runCatching { TerminalToolWindowManager.getWidgetByContent(content) }.getOrNull()
                ?: return@mapNotNull null
            Choice(widget, content.displayName?.takeIf { it.isNotBlank() } ?: "Terminal", registry.findByWidget(widget))
        }
    }

    /** 고른 탭을 레지스트리에 넣고 라벨을 준다 — 이미 등록돼 있으면 그 라벨을 그대로 쓴다 */
    fun pin(choice: Choice): String {
        choice.registered?.let { return it.label }
        val registry = TerminalSessionRegistry.getInstance()
        val label = registry.uniqueLabel(choice.title.ifBlank { "Terminal" })
        registry.register(label, choice.widget, sessionId = null, ours = false)
        return label
    }

    /**
     * 탭이 열려 있는데 하나도 쓸 수 없는 상태인지.
     *
     * 신(Reworked) 터미널 엔진의 탭은 Content 에 JediTerm 위젯이 붙지 않아
     * `getWidgetByContent` 가 항상 null 이다(실측: 신 엔진에서 복원된 탭·새 탭 모두 null,
     * 구 엔진에서는 `ShellTerminalWidget` 이 나온다). 그러면 [list] 가 빈 목록을 주는데,
     * "열린 탭이 없다" 와 구분되지 않아 조용히 새 탭이 열린다 — 이유를 말해줄 수 있게 한다.
     */
    fun hasUnusableTabs(project: Project): Boolean = contents(project).isNotEmpty() && list(project).isEmpty()

    private fun contents(project: Project): List<Content> = runCatching {
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            ?.contentManager?.contents?.toList() ?: emptyList()
    }.getOrDefault(emptyList())
}
