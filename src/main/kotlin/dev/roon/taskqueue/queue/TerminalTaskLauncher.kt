package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.session.SessionWatcher
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.UUID

/**
 * IntelliJ 터미널에서 **대화형** claude 를 띄운다. 실제 Claude Code 화면이 보이고 개입도 된다.
 *
 * 프롬프트는 stdin 으로 밀어넣지 않는다 — CLI 가 위치 인자로 받으므로
 * `claude --session-id <id> "<프롬프트>"` 한 번으로 제출된 상태로 시작한다.
 * 완료 판정은 프로세스가 아니라 jsonl 로 한다(SessionScanner) — 실행 방식과 무관하게 동작.
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
        if (project == null) {
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "열린 프로젝트를 찾을 수 없다: ${task.cwd}"))
            return NoopRunning
        }

        val exe = cliProvider().findExecutable()
        if (exe == null) {
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "claude CLI 를 찾을 수 없다"))
            return NoopRunning
        }

        val sessionId = task.sessionId ?: UUID.randomUUID().toString().also { task.sessionId = it }
        val sessionFile = SessionPaths.sessionFile(task.cwd, sessionId)
        val resume = sessionFile.isFile
        val fromOffset = if (resume) sessionFile.length() else 0L

        val promptFile = writePromptFile(task, sessionId)
        val command = buildCommand(exe, sessionId, resume, promptFile)

        val watch = watcherProvider().watch(
            file = sessionFile,
            fromOffset = fromOffset,
            stopOnTerminal = false,
        ) { state ->
            onState(state)
            // 대화형이라 프로세스 종료 신호가 없다 — jsonl 판정이 완료 신호다
            if (state == SessionState.DONE) {
                onDone(TaskResult(0, state, null, null))
            }
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                val tabName = task.lane.ifEmpty { task.shortLabel().take(20) }
                val widget = TerminalToolWindowManager.getInstance(project)
                    .createShellWidget(task.cwd, tabName, true, true)
                widget.sendCommandToExecute(command)
                onLine("$ $command")
            } catch (e: Exception) {
                watch.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "터미널 실행 실패: ${e.message}"))
            }
        }

        return object : RunningTask {
            // 터미널 세션은 사람이 보는 화면이라 강제 종료하지 않는다 — 감시만 뗀다
            override fun cancel() = watch.cancel()
        }
    }

    /**
     * 프롬프트를 파일로 두고 `"$(cat file)"` 로 넘긴다.
     * 여러 줄·따옴표·백틱이 섞인 프롬프트(에디터 선택 영역 등)를 셸이 해석하지 않게 하는 유일한 안전한 방법.
     */
    private fun writePromptFile(task: TaskEntry, sessionId: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "task-queue")
        dir.mkdirs()
        val file = File(dir, "prompt-$sessionId-${task.attempts}.txt")
        file.writeText(task.prompt)
        return file
    }

    private fun buildCommand(exe: File, sessionId: String, resume: Boolean, promptFile: File): String {
        val flag = if (resume) "--resume" else "--session-id"
        return "${shellQuote(exe.absolutePath)} $flag $sessionId \"\$(cat ${shellQuote(promptFile.absolutePath)})\""
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

    private object NoopRunning : RunningTask {
        override fun cancel() = Unit
    }
}
