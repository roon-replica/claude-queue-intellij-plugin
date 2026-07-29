package dev.roon.taskqueue.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * 우리가 띄운 터미널 탭 ↔ 그 탭에서 도는 claude 세션 ID 매핑.
 *
 * 세션 ID 는 **작업이 아니라 탭에 딸린 값**이다 — 같은 탭에 다음 작업의 프롬프트를 넣으면
 * 그 탭의 기존 claude 프로세스(= 우리가 Stop 훅을 심어둔 프로세스)가 처리하기 때문이다.
 *
 * 탭 제목은 claude 가 실행되면서 바뀌므로(예: "✳ Claude Code") 제목이 아니라
 * 우리가 붙인 라벨로 식별한다.
 */
@Service
class TerminalSessionRegistry {

    private val tabs = mutableListOf<Tab>()

    data class Tab(
        val label: String,
        val widget: ShellTerminalWidget,
        val sessionId: String,
    )

    fun register(label: String, widget: ShellTerminalWidget, sessionId: String) = synchronized(tabs) {
        tabs.removeAll { it.label == label }
        tabs += Tab(label, widget, sessionId)
    }

    /** 살아있는 우리 탭만 */
    fun aliveTabs(): List<Tab> = synchronized(tabs) {
        tabs.removeAll { !isAlive(it.widget) }
        tabs.toList()
    }

    fun find(label: String): Tab? = aliveTabs().firstOrNull { it.label == label }

    /** 라벨이 겹치지 않게 뒤에 번호를 붙인다 */
    fun uniqueLabel(base: String): String {
        val taken = aliveTabs().map { it.label }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /** 탭이 닫히면 tty 세션이 끝난다 */
    private fun isAlive(widget: ShellTerminalWidget): Boolean =
        runCatching { widget.isSessionRunning }.getOrDefault(false)

    companion object {
        fun getInstance(): TerminalSessionRegistry = service()
    }
}
