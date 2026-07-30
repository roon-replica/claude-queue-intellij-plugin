package dev.roon.taskqueue.queue

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.XCollection
import dev.roon.taskqueue.notify.TaskNotifications
import dev.roon.taskqueue.notify.TaskNotifier
import dev.roon.taskqueue.session.SessionState
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 큐 상태 스냅샷 — XML 로 영속화 */
class TaskQueueState {
    @get:XCollection(style = XCollection.Style.v2)
    var tasks: MutableList<TaskEntry> = mutableListOf()

    /**
     * 최근 입력한 프롬프트 (최신순). 카드에서 뽑지 않고 따로 둔다 —
     * '완료 정리' 로 카드를 치워도 히스토리는 남아야 재사용에 쓸모가 있다.
     */
    @get:XCollection(style = XCollection.Style.v2)
    var history: MutableList<String> = mutableListOf()
}

/**
 * 자동작업 큐. application-level 이라 프로젝트 창을 닫아도 살아있다.
 * 한 번에 1건만 실행(순차) — 병렬은 크로스 프로젝트(post-MVP)에서 도입.
 */
@Service
@State(name = "TaskQueue", storages = [Storage("task-queue.xml")])
class TaskQueueService : PersistentStateComponent<TaskQueueState> {

    private var state = TaskQueueState()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 실행 중 작업의 최근 출력 — 메모리 누적을 막기 위해 상한을 둔다 */
    private val logBuffer = ArrayDeque<String>()


    /** 헤드리스 러너 (테스트에서 교체) */
    var launcher: TaskLauncher = ClaudeTaskLauncher()

    /** 터미널 러너 (테스트에서 교체). null 이면 헤드리스로 폴백 */
    var terminalLauncher: TaskLauncher? = TerminalTaskLauncher()

    private fun launcherFor(task: TaskEntry): TaskLauncher = when (task.execMode) {
        ExecMode.TERMINAL -> terminalLauncher ?: launcher
        ExecMode.HEADLESS -> launcher
    }

    /** 알림 창구 (테스트에서 교체) */
    var notifier: TaskNotifications = TaskNotifier

    /** 시간 주입 — 테스트 결정성 확보 */
    var clock: () -> Long = { System.currentTimeMillis() }

    private var running: RunningTask? = null
    private var runningId: String? = null

    /** 자동 진행 여부. false 면 한 건 끝나도 다음을 시작하지 않는다 */
    var autoAdvance: Boolean = true

    // --- 조회 ---

    val tasks: List<TaskEntry> get() = state.tasks.toList()

    fun todos(): List<TaskEntry> = state.tasks.filter { it.status == TaskStatus.TODO }

    fun queued(): List<TaskEntry> = state.tasks.filter { it.status == TaskStatus.QUEUED }

    fun runningTask(): TaskEntry? = runningId?.let { id -> state.tasks.find { it.id == id } }

    fun find(id: String): TaskEntry? = state.tasks.find { it.id == id }

    // --- 변경 ---

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun addLogListener(listener: (String) -> Unit) {
        logListeners += listener
    }

    fun removeLogListener(listener: (String) -> Unit) {
        logListeners -= listener
    }

    /** 최근 입력한 프롬프트 (최신순) */
    fun history(): List<String> = state.history.toList()

    /** 같은 문장은 하나로 합치고 최신을 앞에 둔다 */
    private fun rememberPrompt(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        state.history.remove(text)
        state.history.add(0, text)
        while (state.history.size > MAX_HISTORY) state.history.removeAt(state.history.lastIndex)
    }

    /** 실행 중 작업의 최근 출력 스냅샷 */
    fun recentLog(): List<String> = synchronized(logBuffer) { logBuffer.toList() }


    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        logListeners.forEach { runCatching { it(line) } }
    }

    /**
     * 작업을 **TODO 로만** 추가한다. 바로 실행하지 않는다 — `promote` 로 올려야 돈다.
     * (claude-talk 의 todo → inprogress 모델)
     */
    fun addTodo(
        prompt: String,
        cwd: String,
        execMode: ExecMode = ExecMode.TERMINAL,
        terminalTab: String = "",
    ): TaskEntry {
        rememberPrompt(prompt)
        val task = TaskEntry(UUID.randomUUID().toString(), prompt, cwd, clock())
        task.execMode = execMode
        task.terminalTab = terminalTab.trim()
        state.tasks += task
        notifyChanged()
        return task
    }

    /**
     * TODO → QUEUED. 순서가 오면 실행된다.
     * @param terminalTab 실행할 터미널 탭. null 이면 기존 설정 유지, "" 이면 새 탭
     */
    fun promote(id: String, terminalTab: String? = null) {
        val task = find(id) ?: return
        if (task.status != TaskStatus.TODO && !task.status.isFinished) return
        terminalTab?.let { task.terminalTab = it }
        task.status = TaskStatus.QUEUED
        task.errorMessage = null
        task.exitCode = null
        task.finishedAt = null
        notifyChanged()
        maybeStartNext()
    }

    /** QUEUED → TODO. 실행 중 항목은 되돌리지 않는다(취소를 써야 한다) */
    fun demote(id: String) {
        val task = find(id) ?: return
        if (task.status != TaskStatus.QUEUED) return
        task.status = TaskStatus.TODO
        notifyChanged()
    }

    /** TODO 전부를 대기줄로 올린다 */
    fun runAllTodos(terminalTab: String? = null) {
        val ids = todos().map { it.id }
        ids.forEach { id ->
            find(id)?.let {
                terminalTab?.let { tab -> it.terminalTab = tab }
                it.status = TaskStatus.QUEUED
            }
        }
        if (ids.isNotEmpty()) notifyChanged()
        maybeStartNext()
    }

    /**
     * 프롬프트 수정. **TODO 항목만** — 대기줄에 올라간 뒤엔 곧 전송되므로,
     * 고친 내용과 실제로 claude 가 받은 내용이 어긋날 수 있다.
     */
    fun updatePrompt(id: String, prompt: String): Boolean {
        val task = find(id) ?: return false
        if (task.status != TaskStatus.TODO) return false
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || trimmed == task.prompt) return false
        task.prompt = trimmed
        notifyChanged()
        return true
    }

    /** 대기 중 항목 제거. 실행 중이면 취소 후 제거 */
    fun remove(id: String) {
        if (runningId == id) cancelRunning()
        state.tasks.removeAll { it.id == id }
        notifyChanged()
    }

    fun clearFinished() {
        state.tasks.removeAll { it.status.isFinished }
        notifyChanged()
    }

    /** 대기 항목 순서 이동 (실행 중 항목은 이동 대상이 아니다) */
    fun move(id: String, delta: Int) {
        val idx = state.tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, state.tasks.lastIndex)
        if (target == idx) return
        val item = state.tasks.removeAt(idx)
        state.tasks.add(target, item)
        notifyChanged()
    }

    /**
     * 한 컬럼의 순서를 통째로 다시 놓는다 (드래그앤드롭용).
     * 해당 작업들이 원래 차지하던 전역 슬롯에 `orderedIds` 순서로 채워 넣는다 —
     * 다른 상태의 작업 위치는 건드리지 않는다.
     */
    fun reorderGroup(orderedIds: List<String>) {
        val slots = state.tasks.withIndex()
            .filter { it.value.id in orderedIds }
            .map { it.index }
        if (slots.size != orderedIds.size) return

        val byId = orderedIds.mapNotNull { id -> find(id) }
        if (byId.size != orderedIds.size) return

        slots.forEachIndexed { i, slot -> state.tasks[slot] = byId[i] }
        notifyChanged()
    }

    /** 실패/취소 항목을 다시 대기줄에 올린다 (세션 ID 유지 → 같은 세션에 이어붙음) */
    fun retry(id: String) {
        val task = find(id) ?: return
        if (!task.status.isFinished) return
        promote(id)
    }

    fun cancelRunning() {
        val task = runningTask() ?: return
        running?.cancel()
        running = null
        runningId = null
        finish(task, TaskStatus.CANCELED, exitCode = null, errorMessage = "Canceled by user")
    }

    /**
     * 큐를 멈춘다. **실행 중인 작업은 건드리지 않는다** — 이미 도는 claude 를 중간에
     * 끊으면 작업이 반쯤 된 채로 남고, 어차피 프로세스를 죽이지도 못한다.
     * 대기 중인 항목만 TODO 로 되돌려 의도치 않게 시작되지 않게 한다.
     */
    fun stopQueue() {
        autoAdvance = false
        val waiting = queued()
        waiting.forEach { it.status = TaskStatus.TODO }
        if (waiting.isNotEmpty()) notifyChanged()
    }

    /** 큐 진행 시작 (autoAdvance 를 껐다 켠 뒤 이어서 돌릴 때) */
    fun start() {
        autoAdvance = true
        maybeStartNext()
    }

    fun pause() {
        autoAdvance = false
    }

    // --- 실행 ---

    private fun maybeStartNext() {
        if (!autoAdvance) return
        if (runningId != null) return
        val next = queued().firstOrNull() ?: return
        startTask(next)
    }

    private fun startTask(task: TaskEntry) {
        task.status = TaskStatus.RUNNING
        task.startedAt = clock()
        task.attempts += 1
        runningId = task.id
        synchronized(logBuffer) { logBuffer.clear() }
        notifyChanged()

        running = launcherFor(task).launch(
            task = task,
            onLine = { line -> appendLog(line) },
            onState = { state -> onRunningState(task, state) },
            onDone = { result -> onTaskDone(task, result) },
        )
    }

    /**
     * 실행 중 세션 상태 반영. claude-talk `fix-queue-state` 규칙 이식:
     * - IDLE(사용자 인터럽트) → 자동작업 중단, 현재 항목은 TODO 복귀
     * - WAITING(질문 대기) → 완료가 아니다. 아무것도 하지 않는다
     */
    private fun onRunningState(task: TaskEntry, sessionState: SessionState) {
        task.finalState = sessionState
        if (sessionState == SessionState.IDLE) {
            abortRun(task)
            return
        }
        notifyChanged()
    }

    /** 인터럽트로 멈춘 실행을 중단하고 항목을 TODO 로 되돌린다 */
    private fun abortRun(task: TaskEntry) {
        running?.cancel()
        running = null
        runningId = null
        task.status = TaskStatus.TODO
        task.finishedAt = clock()
        task.errorMessage = "Interrupted by user"
        autoAdvance = false
        notifyChanged()
    }

    /**
     * 완료 판정: 프로세스 종료 코드가 1차 기준, jsonl 판정을 함께 기록한다.
     * exit 0 이라도 result 이벤트가 error 면 실패로 본다.
     */
    private fun onTaskDone(task: TaskEntry, result: TaskResult) {
        running = null
        runningId = null
        task.costUsd = result.costUsd
        task.finalState = result.finalState
        result.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { task.summary = it }

        val failed = result.exitCode != 0 || result.errorMessage != null
        finish(
            task = task,
            status = if (failed) TaskStatus.FAILED else TaskStatus.DONE,
            exitCode = result.exitCode,
            errorMessage = result.errorMessage ?: if (failed) "exit ${result.exitCode}" else null,
        )
        notifier.taskFinished(task)
        maybeStartNext()
        // 다음 작업이 시작되지 않았으면 큐가 다 비었다는 뜻 — 자리를 비운 사람에게 알린다
        if (runningId == null) notifyQueueDrained(task.cwd)
    }

    /** 이번 회차에 처리된 결과만 요약한다 — 이전에 이미 알린 건은 제외 */
    private fun notifyQueueDrained(cwd: String) {
        val finished = state.tasks.filter { it.status.isFinished && !it.notified }
        if (finished.isEmpty()) return
        finished.forEach { it.notified = true }
        notifier.queueDrained(
            done = finished.count { it.status == TaskStatus.DONE },
            failed = finished.count { it.status == TaskStatus.FAILED },
            cwd = cwd,
        )
    }

    private fun finish(task: TaskEntry, status: TaskStatus, exitCode: Int?, errorMessage: String?) {
        task.status = status
        task.exitCode = exitCode
        task.errorMessage = errorMessage
        task.finishedAt = clock()
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    // --- 영속화 ---

    override fun getState(): TaskQueueState = state

    /**
     * IDE 종료로 죽은 RUNNING 은 **TODO** 로 되돌린다.
     * 프로세스는 IDE 와 함께 죽으므로 그대로 두면 영원히 실행중으로 남고,
     * QUEUED 로 두면 IDE 를 켜자마자 의도 없이 다시 돈다.
     */
    override fun loadState(loaded: TaskQueueState) {
        state = loaded
        state.tasks.filter { it.status == TaskStatus.RUNNING }.forEach {
            it.status = TaskStatus.TODO
            it.startedAt = null
        }
        runningId = null
        running = null
    }

    companion object {
        private const val MAX_HISTORY = 20

        private const val MAX_LOG_LINES = 500

        fun getInstance(): TaskQueueService = service()
    }
}
