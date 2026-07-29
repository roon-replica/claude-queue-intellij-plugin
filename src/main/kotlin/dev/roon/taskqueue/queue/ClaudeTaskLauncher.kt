package dev.roon.taskqueue.queue

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionScanner
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.session.SessionWatcher
import java.io.File
import java.util.UUID

/**
 * claude CLI 헤드리스 실행 기반 기본 러너.
 * 서비스는 생성 시점이 아니라 실행 시점에 해석한다 — 생성만으로 플랫폼을 요구하지 않게.
 */
class ClaudeTaskLauncher(
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
        val cli = cliProvider()
        val watcher = watcherProvider()
        val workDir = File(task.cwd)
        val sessionId = task.sessionId ?: UUID.randomUUID().toString().also { task.sessionId = it }
        val sessionFile = SessionPaths.sessionFile(workDir.absolutePath, sessionId)
        // 재시도 시 같은 파일에 이어 쌓이므로, 이번 실행분만 판정 대상으로 삼는다
        val fromOffset = if (sessionFile.isFile) sessionFile.length() else 0L

        var cost: Double? = null
        var errorMessage: String? = null

        val watch = watcher.watch(sessionFile, fromOffset, stopOnTerminal = false) { onState(it) }
        val running = PendingProcess(watch)

        // 프로세스 시작을 EDT 에서 하지 않는다 — UI 스레드에서 프로세스를 띄우면 플랫폼이 막는다
        ApplicationManager.getApplication().executeOnPooledThread {
            if (running.canceled) return@executeOnPooledThread
            val handler = try {
                cli.run(
                    prompt = task.prompt,
                    workDir = workDir,
                    sessionId = sessionId,
                    onEvent = { e ->
                        e.assistantText?.let(onText)
                        if (e.isResult) {
                            cost = e.totalCostUsd
                            e.resultText?.let(onText)
                            if (e.isError) errorMessage = e.resultText?.take(300) ?: "CLI 오류"
                        }
                    },
                    onRawLine = onLine,
                    onFinish = { exitCode ->
                        watch.cancel()
                        val finalState = SessionScanner.sessionState(sessionFile, fromOffset)
                        onDone(TaskResult(exitCode, finalState, cost, errorMessage))
                    },
                )
            } catch (e: Exception) {
                watch.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, e.message ?: "실행 실패"))
                return@executeOnPooledThread
            }
            running.attach(handler)
        }

        return running
    }

    /** 프로세스가 뜨기 전에 취소될 수 있으므로 핸들러를 나중에 붙인다 */
    private class PendingProcess(private val watch: SessionWatcher.Handle) : RunningTask {
        @Volatile
        var canceled = false
            private set

        @Volatile
        private var handler: OSProcessHandler? = null

        fun attach(h: OSProcessHandler) {
            handler = h
            if (canceled) h.destroyProcess()
        }

        override fun cancel() {
            canceled = true
            watch.cancel()
            handler?.destroyProcess()
        }
    }
}
