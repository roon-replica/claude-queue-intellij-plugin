package dev.roon.taskqueue.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ui.content.Content

/**
 * 작업을 실행할 터미널 탭 목록.
 *
 * 두 종류가 있다.
 * - **우리 탭**(`ours=true`): 플러그인이 띄웠고 `--settings` 로 Stop 훅을 심었다.
 *   세션 ID 를 우리가 정했으므로 처음부터 안다.
 * - **외부 탭**(`ours=false`): 사용자가 직접 연 탭. 훅을 심을 수 없고 세션 ID 도 모른다.
 *   프롬프트를 보낸 뒤 jsonl 에서 찾아내 [bindSession] 으로 채운다.
 *
 * 탭 조작은 [TerminalHandle] 뒤에 있다 — 엔진(Classic/Reworked) 차이를 이 클래스가 모른다.
 * 식별은 그 핸들의 **참조**로 한다. 탭 제목은 claude 가 실행되며 바뀌므로(예: "✳ Claude Code")
 * 키로 쓸 수 없다.
 */
@Service
class TerminalSessionRegistry {

    private val tabs = mutableListOf<Tab>()

    class Tab(
        val label: String,
        val handle: TerminalHandle,
        /** 외부 탭은 프롬프트를 보내고 발견하기 전까지 null */
        @Volatile var sessionId: String?,
        /** 우리가 띄워 Stop 훅을 심은 탭인지 */
        val ours: Boolean,
    ) {
        fun write(text: String): Result<Unit> = handle.write(text)
        fun runCommand(command: String): Result<Unit> = handle.runCommand(command)
        fun focus() = runCatching { handle.requestFocus() }

        /** tty 가 붙었는지. 붙기 전에는 명령을 넣어도 유실된다 */
        val ready: Boolean get() = handle.ready

        /** @return null = 돌고 있는지 알 수 없음 */
        fun hasRunningCommand(): Boolean? = handle.hasRunningCommand()

        /** @return null = 프로세스를 들여다볼 수 없음 */
        fun claudeRunning(): Boolean? = handle.claudeRunning()
    }

    /** 같은 물리 탭이 두 번 등록되지 않게 핸들 기준으로도 지운다 */
    fun register(label: String, handle: TerminalHandle, sessionId: String?, ours: Boolean): Tab {
        val tab = Tab(label, handle, sessionId, ours)
        synchronized(tabs) {
            tabs.removeAll { it.label == label || it.handle === handle }
            tabs += tab
        }
        return tab
    }

    /** 외부 탭에서 발견한 세션 ID 를 붙인다 — 다음 작업은 이 ID 를 이어 쓴다 */
    fun bindSession(label: String, sessionId: String) = synchronized(tabs) {
        tabs.firstOrNull { it.label == label }?.sessionId = sessionId
        Unit
    }

    fun unregister(label: String) = synchronized(tabs) {
        tabs.removeAll { it.label == label }
        Unit
    }

    /** 그 Content 에 해당하는 등록된 탭 — 엔진별 매칭은 핸들이 판단한다 */
    fun findByContent(content: Content): Tab? = aliveTabs().firstOrNull { it.handle.matches(content) }

    /** 살아있는 탭만 */
    fun aliveTabs(): List<Tab> = synchronized(tabs) {
        tabs.removeAll { !it.handle.alive }
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

    companion object {
        fun getInstance(): TerminalSessionRegistry = service()
    }
}
