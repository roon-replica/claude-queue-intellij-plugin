package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.hook.StopHookWatcher
import dev.roon.taskqueue.session.SessionFinder
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.session.SessionWatcher
import dev.roon.taskqueue.terminal.TerminalSessionRegistry
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * IntelliJ 터미널에서 **대화형** claude 를 띄운다. 실제 Claude Code 화면이 보이고 개입도 된다.
 *
 * 완료 판정은 탭 종류에 따라 갈린다.
 * - **우리가 띄운 탭**: `--settings` 로 심은 **Stop 훅**. 전역 훅과 병합되며 턴이 끝날 때
 *   세션 ID 가 담긴 JSON 을 남긴다. 검증된 경로이므로 건드리지 않는다.
 * - **사용자가 직접 연 탭**: 훅을 심을 수 없어 세션 ID 를 모른다. 보낸 프롬프트로
 *   jsonl 을 찾아내(`SessionFinder`) 그 파일로 판정한다.
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
                            "그 터미널 탭이 사라졌다 — 실행할 터미널을 다시 골라야 한다",
                        )
                    )
                    return@invokeLater
                }

                val sentAt = System.currentTimeMillis()

                when {
                    // 셸만 떠 있는 탭 — 프롬프트를 타이핑하면 셸에 그대로 들어간다.
                    // 대신 그 탭에서 우리가 claude 를 띄운다 → 훅을 심을 수 있다
                    known != null && !known.ours && known.hasRunningCommand() == false ->
                        startClaudeInTab(known, exe, task, registry, hooks, sentAt, running, onLine, onState, onDone)

                    // claude 가 이미 도는 외부 탭 — 훅이 없으므로 jsonl 로 판정한다
                    known != null && !known.ours ->
                        runOnExternalTab(known, task, sentAt, running, onLine, onState, onDone)

                    // 우리 탭 재사용 — 심어둔 훅이 이 턴의 완료를 알린다
                    known != null ->
                        runOnOurTab(known, task, hooks, sentAt, running, onLine, onState, onDone)

                    else ->
                        runOnNewTab(project, exe, task, registry, hooks, sentAt, running, onLine, onState, onDone)
                }
            } catch (e: Exception) {
                running.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "터미널 실행 실패: ${e.message}"))
            }
        }

        return running
    }

    // --- 실행 경로 ---

    /**
     * 우리가 띄운 새 탭. 세션 ID 를 정하고 Stop 훅을 심어 실행한다.
     * **이 경로는 검증된 동작이므로 순서를 바꾸지 않는다** — 훅 구독이 전송보다 먼저다.
     */
    private fun runOnNewTab(
        project: Project,
        exe: File,
        task: TaskEntry,
        registry: TerminalSessionRegistry,
        hooks: StopHookWatcher,
        sentAt: Long,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        val sessionId = UUID.randomUUID().toString()
        task.sessionId = sessionId
        task.hookSessionId = sessionId

        val command = buildCommand(exe, sessionId, hooks.hookCommand(sessionId), writePromptFile(task))
        val widget = createTab(project, task, registry, sessionId)

        awaitHookStop(hooks, sessionId, sentAt, running, onLine, onState, onDone)

        widget.requestFocus()
        // executeCommand 는 셸 프롬프트 준비를 기다린다 (TTY 직접 쓰기는 초기화 중 유실된다)
        onLine("$ $command")
        runCatching { widget.executeCommand(command) }
            .onFailure { onLine("· 명령 실행 실패: ${it.message}") }
        onState(SessionState.WORKING)
    }

    /**
     * 사용자가 연 탭이지만 **셸만 떠 있는** 경우. 그 탭에서 claude 를 직접 띄운다.
     *
     * 프롬프트를 그냥 타이핑하면 셸 명령으로 들어가 버린다(실측). 여기서 우리가 실행하면
     * 세션 ID 를 정하고 훅도 심을 수 있어 **검증된 훅 경로**로 들어간다.
     */
    private fun startClaudeInTab(
        tab: TerminalSessionRegistry.Tab,
        exe: File,
        task: TaskEntry,
        registry: TerminalSessionRegistry,
        hooks: StopHookWatcher,
        sentAt: Long,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        val shell = tab.shell
        if (shell == null) {
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "그 탭에서는 명령을 실행할 수 없다"))
            return
        }

        val sessionId = UUID.randomUUID().toString()
        task.sessionId = sessionId
        task.hookSessionId = sessionId
        // 이제 이 탭은 우리가 훅을 심은 탭이다 — 다음 작업은 훅 경로로 이어 쓴다
        registry.register(tab.label, tab.widget, sessionId, ours = true)

        val command = buildCommand(exe, sessionId, hooks.hookCommand(sessionId), writePromptFile(task))
        awaitHookStop(hooks, sessionId, sentAt, running, onLine, onState, onDone)

        tab.focus()
        onLine("· 셸 탭에서 claude 를 띄운다")
        onLine("$ $command")
        runCatching { shell.executeCommand(command) }
            .onFailure { onLine("· 명령 실행 실패: ${it.message}") }
        onState(SessionState.WORKING)
    }

    /** 우리 탭 재사용. 그 탭에 심어둔 훅이 이 턴의 완료를 알린다 */
    private fun runOnOurTab(
        tab: TerminalSessionRegistry.Tab,
        task: TaskEntry,
        hooks: StopHookWatcher,
        sentAt: Long,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        val sessionId = tab.sessionId
        if (sessionId == null) {
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "그 탭의 세션 ID 를 알 수 없다"))
            return
        }
        task.sessionId = sessionId
        task.hookSessionId = sessionId

        awaitHookStop(hooks, sessionId, sentAt, running, onLine, onState, onDone)
        sendPrompt(tab, task, onLine)
        onState(SessionState.WORKING)
    }

    /**
     * 사용자가 직접 연 탭. 훅을 심을 수 없어 세션 ID 를 모르므로
     * **보낸 프롬프트로 세션 jsonl 을 찾아내** 그 파일로 완료를 판정한다.
     */
    private fun runOnExternalTab(
        tab: TerminalSessionRegistry.Tab,
        task: TaskEntry,
        sentAt: Long,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        val sent = singleLine(task.prompt)
        sendPrompt(tab, task, onLine)
        onState(SessionState.WORKING)

        // 이 탭의 세션을 이미 아는 경우(두 번째 작업)엔 바로 감시로 넘어간다
        tab.sessionId?.let { id ->
            task.sessionId = id
            watchSession(SessionPaths.sessionFile(task.cwd, id), running, onLine, onDone)
            return
        }

        onLine("· 세션 찾는 중… (보낸 프롬프트로 조회)")
        running.attachDiscovery(
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                if (running.canceled || running.discovered) return@scheduleWithFixedDelay
                val file = SessionFinder.findByPrompt(task.cwd, sent, sentAt)
                if (file != null) {
                    running.markDiscovered()
                    val id = SessionPaths.sessionIdOf(file)
                    task.sessionId = id
                    registryProvider().bindSession(tab.label, id)
                    onLine("· 세션 확인: ${id.take(8)}")
                    watchSession(file, running, onLine, onDone)
                } else if (System.currentTimeMillis() - sentAt > DISCOVER_TIMEOUT_MS) {
                    running.markDiscovered()
                    onDone(
                        TaskResult(
                            -1, SessionState.UNKNOWN, null,
                            "그 탭에서 세션을 찾지 못했다 — claude 가 실행 중인 탭인지 확인해야 한다",
                        )
                    )
                }
            }, 0, DISCOVER_POLL_MS, TimeUnit.MILLISECONDS)
        )
    }

    // --- 공통 ---

    private fun awaitHookStop(
        hooks: StopHookWatcher,
        sessionId: String,
        sentAt: Long,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        // 전송 전에 구독해야 빠른 응답의 Stop 을 놓치지 않는다
        running.attach(
            hooks.awaitStop(sessionId, sentAt) { signal ->
                onLine("· Stop 훅 수신 (세션 ${signal.sessionId.take(8)})")
                running.stopTimeout()
                onState(SessionState.DONE)
                onDone(TaskResult(0, SessionState.DONE, null, null))
            }
        )
        armTimeout(running, onDone)
    }

    /**
     * jsonl 판정 — 외부 탭 전용.
     *
     * **판정 결과를 큐에 그대로 흘리지 않는다.** `IDLE` 을 넘기면 큐가 작업을 TODO 로
     * 되돌리고 자동진행을 끄는데, 전송 직후의 일시적 IDLE 로 그렇게 되면 안 된다.
     * 그래서 `DONE` 만 완료로 인정하고, IDLE 은 연속으로 굳어질 때만 실패 처리한다.
     */
    private fun watchSession(
        file: File,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onDone: (TaskResult) -> Unit,
    ) {
        armTimeout(running, onDone)
        // 전송 이후 엔트리만 본다 — 이전 턴의 완료를 이 작업의 완료로 오판하지 않게
        val offset = maxOf(0L, file.length())
        var idleStreak = 0

        running.attachWatch(
            SessionWatcher.getInstance().watch(file, fromOffset = offset, stopOnTerminal = false) { state ->
                if (running.canceled) return@watch
                when (state) {
                    SessionState.DONE -> {
                        running.stopTimeout()
                        running.stopWatch()
                        onLine("· jsonl 판정: 완료")
                        onDone(TaskResult(0, SessionState.DONE, null, null))
                    }

                    SessionState.IDLE -> {
                        idleStreak++
                        if (idleStreak >= IDLE_STREAK_TO_FAIL) {
                            running.stopTimeout()
                            running.stopWatch()
                            onLine("· jsonl 판정: 중단됨")
                            onDone(
                                TaskResult(-1, SessionState.IDLE, null, "세션이 중단됐다 (사용자 인터럽트)")
                            )
                        }
                    }

                    else -> idleStreak = 0
                }
            }
        )
    }

    private fun armTimeout(running: TerminalRun, onDone: (TaskResult) -> Unit) {
        running.armTimeout {
            onDone(
                TaskResult(
                    -1, SessionState.UNKNOWN, null,
                    "완료 신호를 못 받았다 (${TIMEOUT_MIN}분 초과)",
                )
            )
        }
    }

    /** 이미 claude 가 도는 탭의 입력창에 프롬프트를 넣는다 */
    private fun sendPrompt(tab: TerminalSessionRegistry.Tab, task: TaskEntry, onLine: (String) -> Unit) {
        val text = singleLine(task.prompt)
        tab.focus()
        tab.write(text).onFailure { onLine("· 전송 실패: ${it.message}") }
        onLine("› ${text.take(120)}")
    }

    // --- 터미널 ---

    /** 새 탭을 만들고 레지스트리에 등록한다 — 이후 작업이 이 탭을 골라 이어 쓸 수 있게 */
    private fun createTab(
        project: Project,
        task: TaskEntry,
        registry: TerminalSessionRegistry,
        sessionId: String,
    ): ShellTerminalWidget {
        val label = registry.uniqueLabel(task.shortLabel().take(20).ifEmpty { "claude" })
        val widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(task.cwd, label, true)
        registry.register(label, widget, sessionId, ours = true)
        task.terminalTab = label
        return widget
    }

    /** 대화형 입력은 개행이 곧 전송이라 한 줄로 만든다 */
    private fun singleLine(text: String): String =
        text.replace(Regex("\\s*\\n\\s*"), " ").trim()

    // --- 명령 조립 ---

    /**
     * 프롬프트를 파일로 두고 `"$(cat file)"` 로 넘긴다.
     * 여러 줄·따옴표·백틱이 섞인 프롬프트를 셸이 해석하지 않게 하는 안전한 방법.
     */
    private fun writePromptFile(task: TaskEntry): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "task-queue/prompts")
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

        @Volatile
        private var discovery: ScheduledFuture<*>? = null

        @Volatile
        private var watch: SessionWatcher.Handle? = null

        @Volatile
        var discovered = false
            private set

        fun attach(reg: StopHookWatcher.Registration) {
            registration = reg
            if (canceled) reg.cancel()
        }

        fun attachDiscovery(future: ScheduledFuture<*>) {
            discovery = future
            if (canceled) future.cancel(false)
        }

        /** 세션을 찾았거나 포기했다 — 탐색 폴링을 멈춘다 */
        fun markDiscovered() {
            discovered = true
            discovery?.cancel(false)
            discovery = null
        }

        fun attachWatch(handle: SessionWatcher.Handle) {
            watch = handle
            if (canceled) handle.cancel()
        }

        fun stopWatch() {
            watch?.cancel()
            watch = null
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
            discovery?.cancel(false)
            stopWatch()
            stopTimeout()
        }
    }

    private object NoopRunning : RunningTask {
        override fun cancel() = Unit
    }

    private companion object {
        const val TIMEOUT_MIN = 30

        /** 외부 탭 세션 탐색 */
        const val DISCOVER_POLL_MS = 700L
        const val DISCOVER_TIMEOUT_MS = 60_000L

        /** 일시적 IDLE 로 실패 처리하지 않도록 연속 관측을 요구한다 */
        const val IDLE_STREAK_TO_FAIL = 3
    }
}
