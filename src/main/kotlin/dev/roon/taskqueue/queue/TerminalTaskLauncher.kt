package dev.roon.taskqueue.queue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.hook.StopHookWatcher
import dev.roon.taskqueue.session.SessionFinder
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionScanner
import dev.roon.taskqueue.session.SessionState
import dev.roon.taskqueue.session.SessionWatcher
import dev.roon.taskqueue.terminal.TerminalEngines
import dev.roon.taskqueue.terminal.TerminalHandle
import dev.roon.taskqueue.terminal.TerminalSessionRegistry
import dev.roon.taskqueue.terminal.TerminalTabFocuser
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
            ?: return fail(onDone, "No open project for ${task.cwd}")
        val exe = cliProvider().findExecutable()
            ?: return fail(onDone, "claude CLI not found")

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
                            "That terminal tab is gone — pick a terminal again",
                        )
                    )
                    return@invokeLater
                }

                if (known == null) {
                    runOnNewTab(
                        project, exe, task, registry, hooks,
                        System.currentTimeMillis(), running, onLine, onState, onDone,
                    )
                    return@invokeLater
                }

                // 셸이 아직 뜨는 중일 수 있다 — 준비 전에 명령을 넣으면 유실된다
                awaitTabReady(project, known, running, onLine, onDone) { tab ->
                    val sentAt = System.currentTimeMillis()
                    when {
                        // 우리 탭 재사용 — 심어둔 훅이 이 턴의 완료를 알린다
                        tab.ours ->
                            runOnOurTab(tab, task, hooks, sentAt, running, onLine, onState, onDone)

                        // claude 가 확실히 도는 탭 — 프롬프트를 그 입력창에 넣는다
                        tab.claudeRunning() == true ->
                            runOnExternalTab(tab, task, sentAt, running, onLine, onState, onDone)

                        // claude 는 아닌데 뭔가 돌고 있다 — 남의 작업 위에 프롬프트를 쏘면 안 된다
                        tab.hasRunningCommand() == true -> onDone(
                            TaskResult(
                                -1, SessionState.UNKNOWN, null,
                                "That tab is busy running something else — pick another terminal",
                            )
                        )

                        // 셸만 떠 있다 → 우리가 claude 를 띄운다. 프롬프트를 타이핑하면
                        // 자연어가 셸 명령으로 실행되므로, 확신이 없을 때의 기본값도 이쪽이다
                        else ->
                            startClaudeInTab(tab, exe, task, registry, hooks, sentAt, running, onLine, onState, onDone)
                    }
                }
            } catch (e: Exception) {
                running.cancel()
                onDone(TaskResult(-1, SessionState.UNKNOWN, null, "Terminal launch failed: ${e.message}"))
            }
        }

        return running
    }

    /**
     * 탭의 tty 가 붙고 셸 초기화가 가라앉을 때까지 기다린 뒤 [proceed] 를 EDT 에서 실행한다.
     *
     * 방금 연 탭은 tty 가 아직 없다. 그 상태에서 명령을 넣으면 유실되고,
     * `hasRunningCommands()` 도 셸이 rc 파일을 읽는 중이면 엉뚱하게 답한다.
     * 셸이 뭘 출력하든(rc 경고 등) 상관없이 준비만 기다린다.
     *
     * **먼저 탭을 화면에 띄운다.** 터미널은 탭 UI 가 보일 때까지 세션 시작을 미루므로
     * (`deferSessionStartUntilUiShown`), 가려진 탭은 기다려도 영원히 붙지 않는다(실측).
     */
    private fun awaitTabReady(
        project: Project,
        tab: TerminalSessionRegistry.Tab,
        running: TerminalRun,
        onLine: (String) -> Unit,
        onDone: (TaskResult) -> Unit,
        proceed: (TerminalSessionRegistry.Tab) -> Unit,
    ) {
        if (tab.ready) {
            proceed(tab)
            return
        }

        // 보이게만 하면 세션이 시작된다 — 키보드 포커스는 뺏지 않는다
        TerminalTabFocuser.focus(project, tab.label, moveFocus = false)
        onLine("· waiting for the terminal to start…")
        val startedAt = System.currentTimeMillis()
        running.attachReady(
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                if (running.canceled || running.readyResolved) return@scheduleWithFixedDelay
                when {
                    tab.ready -> {
                        running.markReadyResolved()
                        // 셸이 rc 파일을 읽는 동안은 실행중 판별이 흔들린다 — 조금 가라앉힌다
                        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                            { ApplicationManager.getApplication().invokeLater { proceed(tab) } },
                            SHELL_SETTLE_MS, TimeUnit.MILLISECONDS,
                        )
                    }

                    System.currentTimeMillis() - startedAt > READY_TIMEOUT_MS -> {
                        running.markReadyResolved()
                        onDone(
                            TaskResult(
                                -1, SessionState.UNKNOWN, null,
                                "The terminal did not finish starting up",
                            )
                        )
                    }
                }
            }, 0, READY_POLL_MS, TimeUnit.MILLISECONDS)
        )
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
        val handle = createTab(project, task, registry, sessionId)
        if (handle == null) {
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "Could not open a terminal tab"))
            return
        }

        awaitHookStop(hooks, sessionId, sentAt, running, onLine, onState, onDone)

        // runCommand 는 셸 프롬프트 준비를 기다린다 (TTY 직접 쓰기는 초기화 중 유실된다)
        onLine("$ $command")
        handle.runCommand(command)
            .onFailure { onLine("· command failed: ${it.message}") }
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
        val sessionId = UUID.randomUUID().toString()
        task.sessionId = sessionId
        task.hookSessionId = sessionId
        // 이제 이 탭은 우리가 훅을 심은 탭이다 — 다음 작업은 훅 경로로 이어 쓴다
        registry.register(tab.label, tab.handle, sessionId, ours = true)

        val command = buildCommand(exe, sessionId, hooks.hookCommand(sessionId), writePromptFile(task))
        awaitHookStop(hooks, sessionId, sentAt, running, onLine, onState, onDone)

        // 포커스는 옮기지 않는다 — awaitTabReady 가 이미 탭을 보이게 해뒀다
        onLine("· starting claude in the shell tab")
        onLine("$ $command")
        tab.runCommand(command)
            .onFailure { onLine("· command failed: ${it.message}") }
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
            onDone(TaskResult(-1, SessionState.UNKNOWN, null, "Unknown session id for that tab"))
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

        onLine("· locating session… (matching the prompt we sent)")
        running.attachDiscovery(
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                if (running.canceled || running.discovered) return@scheduleWithFixedDelay
                val file = SessionFinder.findByPrompt(task.cwd, sent, sentAt)
                if (file != null) {
                    running.markDiscovered()
                    val id = SessionPaths.sessionIdOf(file)
                    task.sessionId = id
                    registryProvider().bindSession(tab.label, id)
                    onLine("· session found: ${id.take(8)}")
                    watchSession(file, running, onLine, onDone)
                } else if (System.currentTimeMillis() - sentAt > DISCOVER_TIMEOUT_MS) {
                    running.markDiscovered()
                    onDone(
                        TaskResult(
                            -1, SessionState.UNKNOWN, null,
                            "No session found in that tab — make sure claude is running there",
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
                onLine("· Stop hook received (session ${signal.sessionId.take(8)})")
                running.stopTimeout()
                onState(SessionState.DONE)
                onDone(TaskResult(0, SessionState.DONE, null, null, summary = signal.lastMessage))
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
                        onLine("· jsonl verdict: done")
                        // 훅이 없는 경로라 전사에서 직접 읽는다 (같은 정보)
                        val summary = SessionScanner.lastAssistantText(file).takeIf { it.isNotBlank() }
                        onDone(TaskResult(0, SessionState.DONE, null, null, summary = summary))
                    }

                    SessionState.IDLE -> {
                        idleStreak++
                        if (idleStreak >= IDLE_STREAK_TO_FAIL) {
                            running.stopTimeout()
                            running.stopWatch()
                            onLine("· jsonl verdict: interrupted")
                            onDone(
                                TaskResult(-1, SessionState.IDLE, null, "Session was interrupted by the user")
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
                    "No completion signal after ${TIMEOUT_MIN} minutes",
                )
            )
        }
    }

    /** 이미 claude 가 도는 탭의 입력창에 프롬프트를 넣는다 */
    private fun sendPrompt(tab: TerminalSessionRegistry.Tab, task: TaskEntry, onLine: (String) -> Unit) {
        val text = singleLine(task.prompt)
        // 포커스를 옮기지 않는다 — TTY 쓰기는 Swing 포커스와 무관하다
        tab.write(text).onFailure { onLine("· send failed: ${it.message}") }
        onLine("› ${text.take(120)}")
    }

    // --- 터미널 ---

    /** 새 탭을 만들고 레지스트리에 등록한다 — 이후 작업이 이 탭을 골라 이어 쓸 수 있게 */
    private fun createTab(
        project: Project,
        task: TaskEntry,
        registry: TerminalSessionRegistry,
        sessionId: String,
    ): TerminalHandle? {
        val label = registry.uniqueLabel(task.shortLabel().take(20).ifEmpty { "claude" })
        val handle = TerminalEngines.createTab(project, task.cwd, label) ?: return null
        registry.register(label, handle, sessionId, ours = true)
        task.terminalTab = label
        // 탭이 보이지 않으면 터미널이 세션 시작을 미룬다(deferSessionStartUntilUiShown) —
        // 보이게만 하고 키보드 포커스는 그대로 둔다
        TerminalTabFocuser.focus(project, label, moveFocus = false)
        return handle
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

        /** 탭 준비 대기 — 세션 탐색과 다른 슬롯이어야 한다(둘이 이어서 돈다) */
        @Volatile
        private var readyWait: ScheduledFuture<*>? = null

        @Volatile
        var readyResolved = false
            private set

        fun attachReady(future: ScheduledFuture<*>) {
            readyWait = future
            if (canceled) future.cancel(false)
        }

        fun markReadyResolved() {
            readyResolved = true
            readyWait?.cancel(false)
            readyWait = null
        }

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
            readyWait?.cancel(false)
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

        /** 탭 준비 대기 — 셸이 뜨고 rc 파일을 읽는 시간 */
        const val READY_POLL_MS = 200L
        const val READY_TIMEOUT_MS = 20_000L
        const val SHELL_SETTLE_MS = 600L
    }
}
