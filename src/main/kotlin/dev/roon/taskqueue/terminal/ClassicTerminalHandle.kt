package dev.roon.taskqueue.terminal

import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 구(Classic/JediTerm) 터미널 구현.
 *
 * 여기 담긴 예외 처리들은 전부 실측으로 얻은 것이다 — 주석의 근거를 지우지 말 것.
 */
class ClassicTerminalHandle(val widget: JBTerminalWidget) : TerminalHandle {

    /** 셸 조작이 가능한 탭인지 — 명령 실행/실행중 판별에 필요하다 */
    private val shell: ShellTerminalWidget? get() = widget as? ShellTerminalWidget

    override val ready: Boolean
        get() = runCatching { widget.ttyConnector?.isConnected == true }.getOrDefault(false)

    /**
     * `ttyConnector` 는 **세션 시작 전에도 null** 이다 — null 을 죽음으로 보면 방금 고른 탭이
     * 곧바로 목록에서 사라지고 "탭이 없다" 로 실패한다(실측). null 은 "아직" 으로 본다.
     */
    override val alive: Boolean
        get() = runCatching {
            if (Disposer.isDisposed(widget)) return false
            widget.ttyConnector?.isConnected ?: true
        }.getOrDefault(false)

    /** 쓰기용이라 신 API 어댑터를 그때그때 만들어도 된다 */
    override fun write(text: String): Result<Unit> = runCatching {
        widget.asNewWidget().ttyConnectorAccessor.executeWithTtyConnector { connector ->
            connector.write(CLEAR_INPUT + text + "\r")
        }
    }

    /** executeCommand 는 셸 프롬프트 준비를 기다린다 (TTY 직접 쓰기는 초기화 중 유실된다) */
    override fun runCommand(command: String): Result<Unit> = runCatching {
        val shell = shell ?: error("Cannot run a command in that tab")
        shell.executeCommand(command)
    }

    /**
     * `hasRunningCommands()` 는 셸 통합에 의존해 답을 못 낼 때가 있다. 그때 추측하면
     * 자연어 프롬프트가 셸 명령으로 실행되는 사고가 난다(실측) — 그래서 사실을 본다.
     */
    override fun claudeRunning(): Boolean? {
        val connector = widget.ttyConnector ?: return null
        val shellProcess = (connector as? ProcessTtyConnector)?.process ?: return null
        val handle = handleOf(shellProcess) ?: return null
        return runCatching {
            handle.descendants().anyMatch { it.info().command().orElse("").contains(CLAUDE_MARKER) }
        }.getOrNull()
    }

    /** **셸 통합이 없으면 예외가 나 null 이 된다** — null 을 "돌고 있다" 로 해석하면 안 된다 */
    override fun hasRunningCommand(): Boolean? =
        runCatching { shell?.hasRunningCommands() }.getOrNull()

    override fun requestFocus() {
        runCatching { widget.asNewWidget().requestFocus() }
    }

    /**
     * `getWidgetByContent` 는 Content 당 **같은 객체**를 돌려주므로 참조 비교가 성립한다.
     * (`asNewWidget()` 은 호출마다 새 어댑터라 그렇지 않다 — 실측)
     */
    override fun matches(content: Content): Boolean = runCatching {
        TerminalToolWindowManager.getWidgetByContent(content) === widget
    }.getOrDefault(false)

    /**
     * 셸 프로세스의 핸들.
     *
     * **`toHandle()` 을 바로 쓰면 안 된다** — pty 프로세스(`UnixPtyProcess`)는 이를 재정의하지
     * 않아 `UnsupportedOperationException` 을 던진다. 그러면 판별이 'unknown' 이 되어
     * 직접 연 claude 탭이 'busy' 로 거부됐다. `pid()` 는 구현돼 있으므로 그것으로 얻는다.
     */
    private fun handleOf(process: Process): ProcessHandle? {
        runCatching { return ProcessHandle.of(process.pid()).orElse(null) }
        return runCatching { process.toHandle() }.getOrNull()
    }

    private companion object {
        /** claude 실행 파일 경로에 항상 들어가는 조각 (버전 디렉토리 포함) */
        const val CLAUDE_MARKER = "claude"

        /** 입력창 비우기 = Ctrl-U. 빈 입력창에 보내도 아무 일도 일어나지 않는다 */
        const val CLEAR_INPUT = "\u0015"
    }
}
