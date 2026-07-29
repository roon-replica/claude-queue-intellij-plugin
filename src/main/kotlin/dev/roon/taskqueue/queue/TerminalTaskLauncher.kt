package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.session.SessionWatcher
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IntelliJ 터미널에서 **대화형** claude 를 띄운다. 실제 Claude Code 화면이 보이고 개입도 된다.
 *
 * 프롬프트는 stdin 으로 밀어넣지 않는다 — CLI 가 위치 인자로 받으므로
 * `claude "<프롬프트>"` 한 번으로 제출된 상태로 시작한다.
 *
 * **세션 ID 를 가정하지 않는다.** 대화형은 우리가 준 `--session-id` 대로 파일을 만들지 않는 경우가
 * 있어(실측), 실행 후 프로젝트 폴더에서 새로 갱신된 jsonl 을 찾아 그 파일로 판정한다.
 */
class TerminalTaskLauncher(
    private val cliProvider: () -> ClaudeCli = { ClaudeCli.getInstance() },
    private val watcherProvider: () -> SessionWatcher = { SessionWatcher.getInstance() },
) : TaskLauncher {

    override fun launch(
        task: TaskEntry,
        onLine: (String) -> Unit,
        onText: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ): RunningTask {
        val project = findProject(task.cwd)
            ?: return fail(onDone, "열린 프로젝트를 찾을 수 없다: ${task.cwd}")
        val exe = cliProvider().findExecutable()
            ?: return fail(onDone, "claude CLI 를 찾을 수 없다")

        // 이어가기 대상이 확실한 경우(레인에 이미 파일이 있음)에만 --resume 을 쓴다
        val resumeId = task.sessionId?.takeIf { SessionPaths.sessionFile(task.cwd, it).isFile }
        val launchedAt = System.currentTimeMillis()

        val promptFile = writePromptFile(task)
        val command = buildCommand(exe, resumeId, promptFile)
        val running = TerminalRun()

        ApplicationManager.getApplication().invokeLater {
            if (running.canceled) return@invokeLater
            try {
                val widget = resolveWidget(project, task)
                widget.requestFocus()
                widget.sendCommandToExecute(command)
                onLine("$ $command")
                bindSession(task, resumeId, launchedAt, running, onState, onDone, onLine)
            } catch (e: Exception) {
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "터미널 실행 실패: ${e.message}"))
            }
        }

        return running
    }

    /** 지정 탭이 있으면 재사용, 없으면 새로 만든다. 셸이 뜬 뒤 명령을 보내야 유실되지 않는다 */
    private fun resolveWidget(project: Project, task: TaskEntry): TerminalWidget {
        val manager = TerminalToolWindowManager.getInstance(project)

        if (task.terminalTab.isNotEmpty()) {
            manager.terminalWidgets.firstOrNull { titleOf(it) == task.terminalTab }?.let { return it }
        }

        val tabName = task.terminalTab.ifEmpty { task.lane.ifEmpty { task.shortLabel().take(20) } }
        // deferSessionStartUntilUiShown=false — true 면 셸이 뜨기 전에 보낸 명령이 유실된다
        return manager.createShellWidget(task.cwd, tabName, true, false)
    }

    private fun titleOf(widget: TerminalWidget): String =
        widget.terminalTitle.buildTitle()

    /**
     * 판정 대상 jsonl 을 찾아 붙는다.
     * resume 이면 그 파일, 새 대화면 실행 직후 갱신된 파일을 폴링으로 기다린다.
     */
    private fun bindSession(
        task: TaskEntry,
        resumeId: String?,
        launchedAt: Long,
        running: TerminalRun,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
        onLine: (String) -> Unit,
    ) {
        if (resumeId != null) {
            val file = SessionPaths.sessionFile(task.cwd, resumeId)
            startWatch(task, file, file.length(), running, onState, onDone)
            return
        }

        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        val deadline = launchedAt + BIND_TIMEOUT_MS
        lateinit var poll: Runnable
        poll = Runnable {
            if (running.canceled) return@Runnable
            val found = SessionPaths.newestSessionFileSince(task.cwd, launchedAt - CLOCK_SLACK_MS)
            if (found != null) {
                task.sessionId = SessionPaths.sessionIdOf(found)
                onLine("· 세션 감지: ${task.sessionId}")
                startWatch(task, found, 0, running, onState, onDone)
                return@Runnable
            }
            if (System.currentTimeMillis() > deadline) {
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "세션 파일을 찾지 못했다 (터미널 실행 확인 필요)"))
                return@Runnable
            }
            executor.schedule(poll, BIND_POLL_MS, TimeUnit.MILLISECONDS)
        }
        executor.schedule(poll, BIND_POLL_MS, TimeUnit.MILLISECONDS)
    }

    /** 대화형은 프로세스 종료 신호가 없다 — jsonl 판정이 완료 신호다 */
    private fun startWatch(
        task: TaskEntry,
        file: File,
        fromOffset: Long,
        running: TerminalRun,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        val watch = watcherProvider().watch(file, fromOffset, stopOnTerminal = false) { state ->
            onState(state)
            if (state == SessionState.DONE) onDone(TaskResult(0, state, null, null))
        }
        running.attach(watch)
    }

    /**
     * 프롬프트를 파일로 두고 `"$(cat file)"` 로 넘긴다.
     * 여러 줄·따옴표·백틱이 섞인 프롬프트를 셸이 해석하지 않게 하는 안전한 방법.
     */
    private fun writePromptFile(task: TaskEntry): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "task-queue")
        dir.mkdirs()
        val file = File(dir, "prompt-${task.id}-${task.attempts}-${UUID.randomUUID()}.txt")
        file.writeText(task.prompt)
        file.deleteOnExit()
        return file
    }

    private fun buildCommand(exe: File, resumeId: String?, promptFile: File): String = buildString {
        append(shellQuote(exe.absolutePath))
        if (resumeId != null) append(" --resume ").append(resumeId)
        append(" \"\$(cat ").append(shellQuote(promptFile.absolutePath)).append(")\"")
    }

    private fun shellQuote(path: String): String =
        if (path.none { it.isWhitespace() || it in "'\"$`\\" }) path
        else "'" + path.replace("'", "'\\''") + "'"

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

    /** 터미널 세션은 사람이 보는 화면이라 강제 종료하지 않는다 — 감시만 뗀다 */
    private class TerminalRun : RunningTask {
        @Volatile
        var canceled = false
            private set

        @Volatile
        private var watch: SessionWatcher.Handle? = null

        fun attach(handle: SessionWatcher.Handle) {
            watch = handle
            if (canceled) handle.cancel()
        }

        override fun cancel() {
            canceled = true
            watch?.cancel()
        }
    }

    private object NoopRunning : RunningTask {
        override fun cancel() = Unit
    }

    private companion object {
        const val BIND_POLL_MS = 400L
        const val BIND_TIMEOUT_MS = 60_000L

        /** 파일 mtime 이 실행 시각보다 살짝 이를 수 있어 여유를 둔다 */
        const val CLOCK_SLACK_MS = 2_000L
    }
}
