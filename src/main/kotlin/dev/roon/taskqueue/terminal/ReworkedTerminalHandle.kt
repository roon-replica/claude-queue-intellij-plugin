package dev.roon.taskqueue.terminal

import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus

/**
 * 신(Reworked) 터미널 구현 — 2026.1 의 기본 엔진.
 *
 * **이 파일만이 `com.intellij.terminal.frontend.*` 를 안다.** 그 API 는
 * `@ApiStatus.Experimental` 이라 예고 없이 바뀔 수 있어, 깨졌을 때 여기만 고치면 되게 가둔다.
 * 구버전 IDE 에는 이 클래스들이 없으므로 [TerminalEngines] 가 있는지 확인한 뒤에만 로드한다.
 *
 * 구 엔진과 달리 pty 프로세스를 직접 잡을 수 없다. 대신 셸 통합이 주는 정보를 쓴다 —
 * 실행 중인 명령 이름(`executedCommand`)까지 알 수 있어 "claude 가 도는지" 를 정확히 답한다.
 */
class ReworkedTerminalHandle(private val tab: TerminalToolWindowTab) : TerminalHandle {

    private val view: TerminalView get() = tab.view

    override val ready: Boolean
        get() = runCatching { view.sessionState.value is TerminalViewSessionState.Running }
            .getOrDefault(false)

    override val alive: Boolean
        get() = runCatching { view.sessionState.value !is TerminalViewSessionState.Terminated }
            .getOrDefault(false)

    /**
     * 입력창에 한 줄 넣고 실행한다.
     *
     * 비우기(Ctrl-U)를 **따로 보낸다** — `shouldExecute()` 는 뒤에 개행을 붙이므로
     * 한 번에 합치면 비우기와 본문 사이가 아니라 끝에 개행이 붙어 순서가 어긋난다.
     */
    override fun write(text: String): Result<Unit> = runCatching {
        view.sendText(CLEAR_INPUT)
        view.createSendTextBuilder().shouldExecute().send(text)
    }

    override fun runCommand(command: String): Result<Unit> = runCatching {
        view.createSendTextBuilder().shouldExecute().send(command)
    }

    /**
     * 실행 중인 명령이 claude 인지 셸 통합에 묻는다.
     *
     * `hasChildProcesses()` 는 suspend 인 데다 **무엇이** 도는지 알려주지 않아 쓰지 않는다 —
     * "뭔가 돈다" 를 "claude 가 돈다" 로 오해하면 남의 작업 위에 프롬프트를 쏘게 된다.
     *
     * 셸 통합이 아직 안 붙었으면 **모른다(null)** 고 답한다. 블로킹하지 않는다.
     */
    override fun claudeRunning(): Boolean? = runCatching {
        val integration = view.shellIntegrationDeferred.takeIf { it.isCompleted }?.getCompleted()
            ?: return null
        val block = integration.blocksModel.activeBlock as? TerminalCommandBlock ?: return false
        // exitCode 가 있으면 이미 끝난 명령이다
        block.exitCode == null && block.executedCommand?.contains(CLAUDE_MARKER) == true
    }.getOrNull()

    /** @return null = 셸 통합이 아직 없어 판별 불가 */
    override fun hasRunningCommand(): Boolean? = runCatching {
        val integration = view.shellIntegrationDeferred.takeIf { it.isCompleted }?.getCompleted()
            ?: return null
        integration.outputStatus.value is TerminalOutputStatus.ExecutingCommand
    }.getOrNull()

    override fun userTitle(): String? =
        runCatching { view.title.userDefinedTitle?.takeIf { it.isNotBlank() } }.getOrNull()

    override fun setUserTitle(title: String) {
        runCatching { view.title.change { userDefinedTitle = title } }
    }

    override fun requestFocus() {
        runCatching { view.preferredFocusableComponent.requestFocusInWindow() }
    }

    override fun matches(content: Content): Boolean =
        runCatching { tab.content === content }.getOrDefault(false)

    private companion object {
        const val CLAUDE_MARKER = "claude"

        /** 입력창 비우기 = Ctrl-U */
        const val CLEAR_INPUT = "\u0015"
    }
}
