package dev.roon.taskqueue.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * 작업을 실행할 터미널 탭 목록.
 *
 * 두 종류가 있다.
 * - **우리 탭**(`ours=true`): 플러그인이 띄웠고 `--settings` 로 Stop 훅을 심었다.
 *   세션 ID 를 우리가 정했으므로 처음부터 안다.
 * - **외부 탭**(`ours=false`): 사용자가 직접 연 탭. 훅을 심을 수 없고 세션 ID 도 모른다.
 *   프롬프트를 보낸 뒤 jsonl 에서 찾아내 [bindSession] 으로 채운다.
 *
 * 식별은 `JBTerminalWidget` 참조로 한다. **`asNewWidget()` 은 호출마다 새 어댑터를 만들어
 * 동일성 비교가 깨진다**(실측: 캐시 필드가 없다) — 그래서 신 API 객체를 키로 쓰지 않는다.
 * 탭 제목도 claude 가 실행되며 바뀌므로(예: "✳ Claude Code") 키로 쓸 수 없다.
 */
@Service
class TerminalSessionRegistry {

    private val tabs = mutableListOf<Tab>()

    class Tab(
        val label: String,
        val widget: JBTerminalWidget,
        /** 외부 탭은 프롬프트를 보내고 발견하기 전까지 null */
        @Volatile var sessionId: String?,
        /** 우리가 띄워 Stop 훅을 심은 탭인지 */
        val ours: Boolean,
    ) {
        /** 대화형 입력창에 한 줄 밀어넣는다. 쓰기용이라 신 API 어댑터를 그때그때 만들어도 된다 */
        fun write(text: String): Result<Unit> = runCatching {
            widget.asNewWidget().ttyConnectorAccessor.executeWithTtyConnector { connector ->
                connector.write(text + "\r")
            }
        }

        fun focus() = runCatching { widget.asNewWidget().requestFocus() }

        /** 셸 조작이 가능한 탭인지 — 명령 실행/실행중 판별에 필요하다 */
        val shell: ShellTerminalWidget? get() = widget as? ShellTerminalWidget

        /** tty 가 붙었는지. 붙기 전에는 명령을 넣어도 유실된다 */
        val ready: Boolean
            get() = runCatching { widget.ttyConnector?.isConnected == true }.getOrDefault(false)

        /**
         * 그 탭에서 무언가 돌고 있는지. claude 가 켜져 있으면 true.
         * 판단 불가면 null — 그때는 "돌고 있다" 고 보수적으로 다룬다.
         */
        fun hasRunningCommand(): Boolean? =
            runCatching { shell?.hasRunningCommands() }.getOrNull()
    }

    /** 같은 물리 탭이 두 번 등록되지 않게 위젯 기준으로도 지운다 */
    fun register(label: String, widget: JBTerminalWidget, sessionId: String?, ours: Boolean): Tab {
        val tab = Tab(label, widget, sessionId, ours)
        synchronized(tabs) {
            tabs.removeAll { it.label == label || it.widget === widget }
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

    fun findByWidget(widget: JBTerminalWidget): Tab? =
        aliveTabs().firstOrNull { it.widget === widget }

    /** 살아있는 탭만 */
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

    /**
     * 탭이 닫혔는지.
     *
     * `ttyConnector` 는 **세션 시작 전에도 null** 이다 — null 을 죽음으로 보면 방금 고른 탭이
     * 곧바로 목록에서 사라지고 "탭이 없다" 로 실패한다(실측). null 은 "아직" 으로 본다.
     */
    private fun isAlive(widget: JBTerminalWidget): Boolean = runCatching {
        if (Disposer.isDisposed(widget)) return false
        widget.ttyConnector?.isConnected ?: true
    }.getOrDefault(false)

    companion object {
        fun getInstance(): TerminalSessionRegistry = service()
    }
}
