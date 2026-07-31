package dev.roon.taskqueue.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content

/**
 * 프로젝트에 열려 있는 터미널 탭 조회.
 *
 * 툴윈도의 Content 를 훑고, 각 Content 를 다룰 수 있는 [TerminalHandle] 을
 * [TerminalEngines] 에게 물어본다 — 엔진 판별은 그쪽 책임이다.
 */
object TerminalTabs {

    class Choice(
        val handle: TerminalHandle,
        val title: String,
        /** 이미 등록된 탭이면 그 정보 — 없으면 고를 때 등록한다 */
        val registered: TerminalSessionRegistry.Tab?,
    ) {
        /** 팔레트에 보여줄 한 줄 — 고르면 뭐가 일어나는지 알 수 있게 */
        val display: String
            get() {
                val session = registered?.sessionId
                return when {
                    session != null -> "$title  (continue — session ${session.take(8)})"
                    handle.claudeRunning() == true -> "$title  (claude running — continue here)"
                    handle.hasRunningCommand() == true -> "$title  (busy — running something else)"
                    else -> "$title  (shell — claude will be started here)"
                }
            }
    }

    fun list(project: Project): List<Choice> {
        val registry = TerminalSessionRegistry.getInstance()
        return contents(project).mapNotNull { content ->
            val handle = TerminalEngines.handleFor(project, content) ?: return@mapNotNull null
            Choice(
                handle = handle,
                title = content.displayName?.takeIf { it.isNotBlank() } ?: "Terminal",
                registered = registry.findByContent(content),
            )
        }
    }

    /** 고른 탭을 레지스트리에 넣고 라벨을 준다 — 이미 등록돼 있으면 그 라벨을 그대로 쓴다 */
    fun pin(choice: Choice): String {
        choice.registered?.let { return it.label }
        val registry = TerminalSessionRegistry.getInstance()
        val label = registry.uniqueLabel(choice.title.ifBlank { "Terminal" })
        registry.register(label, choice.handle, sessionId = null, ours = false)
        return label
    }

    /**
     * 아직 등록되지 않은 탭을 등록하고 라벨을 준다 (팔레트를 거치지 않고 드래그로 고른 경우).
     *
     * **등록되지 않은 탭 이름은 실행에 쓸 수 없다** — 런처는 레지스트리에서 찾으므로
     * 없으면 "That terminal tab is gone" 으로 실패한다.
     *
     * @return 라벨, 또는 다룰 수 없는 탭이면 null
     */
    fun pinContent(project: Project, content: Content): String? {
        val registry = TerminalSessionRegistry.getInstance()
        registry.findByContent(content)?.let { return it.label }
        val handle = TerminalEngines.handleFor(project, content) ?: return null
        val title = content.displayName?.takeIf { it.isNotBlank() } ?: "Terminal"
        val label = registry.uniqueLabel(title)
        registry.register(label, handle, sessionId = null, ours = false)
        return label
    }

    /**
     * 탭이 열려 있는데 하나도 쓸 수 없는 상태인지.
     *
     * 지원하지 않는 엔진의 탭은 [TerminalEngines] 가 핸들을 만들지 못해 [list] 가 빈 목록을
     * 준다. "열린 탭이 없다" 와 구분되지 않으면 조용히 새 탭이 열려 이유를 알 수 없다.
     */
    fun hasUnusableTabs(project: Project): Boolean =
        contents(project).isNotEmpty() && list(project).isEmpty()

    fun contents(project: Project): List<Content> = runCatching {
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            ?.contentManager?.contents?.toList() ?: emptyList()
    }.getOrDefault(emptyList())
}
