package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
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
        /** 셸만 떠 있으면 우리가 claude 를 띄운다 — 무언가 돌고 있으면 claude 로 본다 */
        private val idleShell: Boolean
            get() = runCatching { (widget as? ShellTerminalWidget)?.hasRunningCommands() == false }
                .getOrDefault(false)

        /** 팔레트에 보여줄 한 줄 — 고르면 뭐가 일어나는지 알 수 있게 */
        val display: String
            get() {
                val session = registered?.sessionId
                return when {
                    session != null -> "$title  (continue — session ${session.take(8)})"
                    idleShell -> "$title  (shell — claude will be started here)"
                    else -> "$title  (claude running — continue here)"
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

    private fun contents(project: Project): List<Content> = runCatching {
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            ?.contentManager?.contents?.toList() ?: emptyList()
    }.getOrDefault(emptyList())
}
