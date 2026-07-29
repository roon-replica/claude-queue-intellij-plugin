package dev.roon.taskqueue.queue

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

        val handler = cli.run(
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

        return object : RunningTask {
            override fun cancel() {
                watch.cancel()
                handler.destroyProcess()
            }
        }
    }
}
