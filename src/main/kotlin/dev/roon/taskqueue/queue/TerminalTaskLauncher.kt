package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.hook.StopHookWatcher
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.terminal.TerminalSessionRegistry
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * IntelliJ 터미널에서 **대화형** claude 를 띄운다. 실제 Claude Code 화면이 보이고 개입도 된다.
 *
 * 새 탭은 **터미널의 셸 자체를 claude 로 지정해** 시작한다(`shellCommand`).
 * 셸에 명령을 타이핑하는 방식은 초기화 타이밍에 따라 유실돼 실패했다(실측) —
 * argv 로 직접 넘기면 인용·타이핑 문제가 아예 없다.
 *
 * 완료 판정은 **Stop 훅**으로 한다. 대화형 세션은 열려 있는 동안 전사(jsonl)를 쓰지 않아
 * 파일 기반 판정이 불가하다(실측).
 */
class TerminalTaskLauncher(
    private val cliProvider: () -> ClaudeCli = { ClaudeCli.getInstance() },
    private val hookProvider: () -> StopHookWatcher = { StopHookWatcher.getInstance() },
    private val registryProvider: () -> TerminalSessionRegistry = { TerminalSessionRegistry.getInstance() },
) : TaskLauncher {

    override fun launch(
        task: TaskEntry,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ): RunningTask {
        val project = findProject(task.cwd)
            ?: return fail(onDone, "열린 프로젝트를 찾을 수 없다: ${task.cwd}")
        val exe = cliProvider().findExecutable()
            ?: return fail(onDone, "claude CLI 를 찾을 수 없다")

        val hooks = hookProvider()
        val running = TerminalRun()

        ApplicationManager.getApplication().invokeLater {
            if (running.canceled) return@invokeLater
            try {
                val registry = registryProvider()
                // 세션 ID 는 작업이 아니라 탭에 딸린 값 — 같은 탭이면 그 탭의 claude 가 처리한다
                val known = task.terminalTab.takeIf { it.isNotEmpty() }?.let { registry.find(it) }

                if (task.terminalTab.isNotEmpty() && known == null) {
                    onDone(
                        TaskResult(
                            -1, SessionState.UNKNOWN, null,
                            "외부 터미널이라 완료 판정 불가 — 새 터미널로 실행해야 한다",
                        )
                    )
                    return@invokeLater
                }

                val sessionId = known?.sessionId ?: UUID.randomUUID().toString()
                task.sessionId = sessionId
                task.hookSessionId = sessionId
                val sentAt = System.currentTimeMillis()

                // 전송 전에 구독해야 빠른 응답의 Stop 을 놓치지 않는다
                running.attach(
                    hooks.awaitStop(sessionId, sentAt) { signal ->
                        onLine("· Stop 훅 수신 (세션 ${signal.sessionId.take(8)})")
                        running.stopTimeout()
                        onState(SessionState.DONE)
                        onDone(TaskResult(0, SessionState.DONE, null, null))
                    }
                )
                running.armTimeout {
                    onDone(
                        TaskResult(
                            -1, SessionState.UNKNOWN, null,
                            "완료 신호를 못 받았다 (${TIMEOUT_MIN}분 초과)",
                        )
                    )
                }

                if (known != null) {
                    // 이미 claude 가 도는 탭 → 그 입력창에 프롬프트를 넣는다
                    val text = singleLine(task.prompt)
                    known.widget.requestFocus()
                    known.widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
                        runCatching { connector.write(text + "\r") }
                            .onFailure { onLine("· 전송 실패: ${it.message}") }
                    }
                    onLine("› $text".take(140))
                } else {
                    val argv = buildArgv(exe, sessionId, hooks.hookCommand(sessionId), task.prompt)
                    val label = openClaudeTab(project, task, registry, sessionId, argv)
                    onLine("$ claude --session-id ${sessionId.take(8)}…  (탭: $label)")
                }

                onState(SessionState.WORKING)
            } catch (e: Exception) {
                running.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "터미널 실행 실패: ${e.message}"))
            }
        }

        return running
    }

    /**
     * 터미널을 열되 셸 대신 claude 를 직접 실행한다.
     * `deferSessionStartUntilUiShown=true` 로 만들고 시작 전에 셸 명령을 바꿔치기한다.
     */
    private fun openClaudeTab(
        project: Project,
        task: TaskEntry,
        registry: TerminalSessionRegistry,
        sessionId: String,
        argv: List<String>,
    ): String {
        val label = registry.uniqueLabel(task.shortLabel().take(20).ifEmpty { "claude" })
        val widget = TerminalToolWindowManager.getInstance(project)
            .createShellWidget(task.cwd, label, true, true)
        widget.shellCommand = argv
        registry.register(label, widget, sessionId)
        task.terminalTab = label
        return label
    }

    /** 대화형 입력은 개행이 곧 전송이라 한 줄로 만든다 */
    private fun singleLine(text: String): String =
        text.replace(Regex("\\s*\\n\\s*"), " ").trim()

    /** argv 로 직접 넘기므로 셸 인용이 필요 없다 */
    private fun buildArgv(exe: File, sessionId: String, hookCommand: String, prompt: String): List<String> {
        val settings = """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":${jsonString(hookCommand)}}]}]}}"""
        return listOf(
            exe.absolutePath,
            "--session-id", sessionId,
            "--settings", settings,
            prompt,
        )
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun findProject(cwd: String): Project? {
        val target = File(cwd).absolutePath
        return ProjectManager.getInstance().openProjects.firstOrNull { p ->
            p.basePath?.let { File(it).absolutePath } == target
        }
    }

    private fun fail(onDone: (TaskResult) -> Unit, message: String): RunningTask {
        onDone(TaskResult(-1, SessionState.UNKNOWN, null, message))
        return NoopRunning
    }

    /** 터미널 세션은 사람이 보는 화면이라 강제 종료하지 않는다 — 구독만 뗀다 */
    private class TerminalRun : RunningTask {
        @Volatile
        var canceled = false
            private set

        @Volatile
        private var registration: StopHookWatcher.Registration? = null

        @Volatile
        private var timeout: ScheduledFuture<*>? = null

        fun attach(reg: StopHookWatcher.Registration) {
            registration = reg
            if (canceled) reg.cancel()
        }

        fun armTimeout(onTimeout: () -> Unit) {
            timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!canceled) {
                    registration?.cancel()
                    onTimeout()
                }
            }, TIMEOUT_MIN.toLong(), TimeUnit.MINUTES)
        }

        fun stopTimeout() {
            timeout?.cancel(false)
            timeout = null
        }

        override fun cancel() {
            canceled = true
            registration?.cancel()
            stopTimeout()
        }
    }

    private object NoopRunning : RunningTask {
        override fun cancel() = Unit
    }

    private companion object {
        const val TIMEOUT_MIN = 30
    }
}
