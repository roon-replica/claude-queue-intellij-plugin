package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.hook.StopHookWatcher
import dev.roon.taskqueue.session.SessionState
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * IntelliJ 터미널에서 **대화형** claude 를 띄운다. 실제 Claude Code 화면이 보이고 개입도 된다.
 *
 * 완료 판정은 **Stop 훅**으로 한다. 대화형 세션은 열려 있는 동안 전사(jsonl)를 쓰지 않아
 * 파일 기반 판정이 불가하다(실측). `--settings` 로 심은 Stop 훅은 전역 훅과 병합되며,
 * 턴이 끝날 때 세션 ID 가 담긴 JSON 을 남긴다.
 */
class TerminalTaskLauncher(
    private val cliProvider: () -> ClaudeCli = { ClaudeCli.getInstance() },
    private val hookProvider: () -> StopHookWatcher = { StopHookWatcher.getInstance() },
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
                val existing = findExistingTab(project, task.terminalTab)
                val reuse = existing != null && hasRunningCommand(existing)

                // 이미 도는 세션에는 훅을 심을 수 없다 — 우리가 띄운 세션이어야 판정이 가능하다
                if (reuse && task.hookSessionId.isEmpty()) {
                    onDone(
                        TaskResult(
                            -1, SessionState.UNKNOWN, null,
                            "외부 세션이라 완료 판정 불가 — 새 터미널로 실행해야 한다",
                        )
                    )
                    return@invokeLater
                }

                val sessionId = task.hookSessionId.ifEmpty { UUID.randomUUID().toString() }
                task.sessionId = sessionId
                task.hookSessionId = sessionId

                val payload =
                    if (reuse) singleLine(task.prompt)
                    else buildCommand(exe, sessionId, hooks.hookCommand(), writePromptFile(task))

                val widget = existing ?: createTab(project, task)
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

                widget.requestFocus()
                widget.executeWithTtyConnector { connector ->
                    runCatching { connector.write(payload + "\r") }
                        .onFailure { onLine("· 전송 실패: ${it.message}") }
                }

                onState(SessionState.WORKING)
                onLine(
                    if (reuse) "› ${singleLine(task.prompt).take(120)}"
                    else "$ claude (세션 ${sessionId.take(8)}, Stop 훅 심음)"
                )
            } catch (e: Exception) {
                running.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "터미널 실행 실패: ${e.message}"))
            }
        }

        return running
    }

    // --- 터미널 ---

    private fun findExistingTab(project: Project, tabName: String): ShellTerminalWidget? {
        if (tabName.isEmpty()) return null
        return TerminalToolWindowManager.getInstance(project).widgets
            .filterIsInstance<ShellTerminalWidget>()
            .firstOrNull { titleOf(it) == tabName }
    }

    private fun createTab(project: Project, task: TaskEntry): ShellTerminalWidget {
        val tabName = task.terminalTab.ifEmpty { task.shortLabel().take(20) }
        return TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(task.cwd, tabName, true)
    }

    private fun hasRunningCommand(widget: ShellTerminalWidget): Boolean =
        runCatching { widget.hasRunningCommands() }.getOrDefault(false)

    private fun titleOf(widget: ShellTerminalWidget): String =
        runCatching { widget.terminalTitle.buildTitle() }.getOrDefault("")

    /** 대화형 입력은 개행이 곧 전송이라 한 줄로 만든다 */
    private fun singleLine(text: String): String =
        text.replace(Regex("\\s*\\n\\s*"), " ").trim()

    // --- 명령 조립 ---

    /**
     * 프롬프트를 파일로 두고 `"$(cat file)"` 로 넘긴다.
     * 여러 줄·따옴표·백틱이 섞인 프롬프트를 셸이 해석하지 않게 하는 안전한 방법.
     */
    private fun writePromptFile(task: TaskEntry): File {
        val dir = File(System.getProperty("user.home"), ".task-queue/prompts")
        dir.mkdirs()
        val file = File(dir, "prompt-${task.id}-${task.attempts}.txt")
        file.writeText(task.prompt)
        return file
    }

    private fun buildCommand(exe: File, sessionId: String, hookCommand: String, promptFile: File): String {
        val settings = """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":${jsonString(hookCommand)}}]}]}}"""
        return buildString {
            append(shellQuote(exe.absolutePath))
            append(" --session-id ").append(sessionId)
            append(" --settings ").append(shellQuote(settings))
            append(" \"\$(cat ").append(shellQuote(promptFile.absolutePath)).append(")\"")
        }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun shellQuote(text: String): String =
        if (text.none { it.isWhitespace() || it in "'\"$`\\{}" }) text
        else "'" + text.replace("'", "'\\''") + "'"

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
