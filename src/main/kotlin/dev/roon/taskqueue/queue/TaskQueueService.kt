package dev.roon.taskqueue.queue

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.XCollection
import dev.roon.taskqueue.nav.FileRefs
import dev.roon.taskqueue.session.SessionState
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 레인 = 이름 붙인 대화 세션. cwd 별로 세션이 다르므로 cwd 도 함께 보관 */
class LaneEntry() {
    var name: String = ""
    var cwd: String = ""
    var sessionId: String = ""

    constructor(name: String, cwd: String, sessionId: String) : this() {
        this.name = name
        this.cwd = cwd
        this.sessionId = sessionId
    }
}

/** 큐 상태 스냅샷 — XML 로 영속화 */
class TaskQueueState {
    @get:XCollection(style = XCollection.Style.v2)
    var tasks: MutableList<TaskEntry> = mutableListOf()

    @get:XCollection(style = XCollection.Style.v2)
    var lanes: MutableList<LaneEntry> = mutableListOf()
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

    /** taskId → 결과에서 뽑은 file:line 참조 */
    private val refsByTask = mutableMapOf<String, MutableList<FileRefs.Ref>>()

    /** 테스트에서 교체 가능 */
    var launcher: TaskLauncher = ClaudeTaskLauncher()

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

    /** 실행 중 작업의 최근 출력 스냅샷 */
    fun recentLog(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    /** 작업 결과에서 뽑은 file:line 참조 (영속화하지 않는다 — 세션 한정) */
    fun refs(taskId: String): List<FileRefs.Ref> = synchronized(refsByTask) {
        refsByTask[taskId]?.toList() ?: emptyList()
    }

    private fun collectRefs(task: TaskEntry, text: String) {
        val found = FileRefs.extract(text, File(task.cwd))
        if (found.isEmpty()) return
        synchronized(refsByTask) {
            val list = refsByTask.getOrPut(task.id) { mutableListOf() }
            found.forEach { if (it !in list) list += it }
        }
        notifyChanged()
    }

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
     * @param lane 빈 값이면 독립 세션. 이름을 주면 그 레인의 대화를 이어 쓴다
     */
    fun addTodo(prompt: String, cwd: String, lane: String = ""): TaskEntry {
        val task = TaskEntry(UUID.randomUUID().toString(), prompt, cwd, clock())
        task.lane = lane.trim()
        state.tasks += task
        // 실행 전에도 레인이 목록에 보여야 한다 — 세션 ID 는 첫 실행 때 채운다
        if (task.lane.isNotEmpty()) registerLane(cwd, task.lane)
        notifyChanged()
        return task
    }

    // --- 레인 ---

    /** 해당 cwd 에 등록된 레인 이름 */
    fun lanes(cwd: String): List<String> =
        state.lanes.filter { it.cwd == cwd }.map { it.name }.distinct().sorted()

    /** 레인의 현재 세션 ID (없으면 null) */
    fun laneSessionId(cwd: String, lane: String): String? =
        state.lanes.firstOrNull { it.cwd == cwd && it.name == lane }?.sessionId?.ifEmpty { null }

    /** 레인을 비운다 — 다음 실행부터 새 대화로 시작 */
    fun resetLane(cwd: String, lane: String) {
        state.lanes.removeAll { it.cwd == cwd && it.name == lane }
        notifyChanged()
    }

    /** 세션 ID 없이 레인만 등록 (목록 표시용) */
    private fun registerLane(cwd: String, lane: String) {
        if (state.lanes.none { it.cwd == cwd && it.name == lane }) {
            state.lanes += LaneEntry(lane, cwd, "")
        }
    }

    /** 레인의 세션 ID 를 확보한다. 비어 있으면 만들어 저장 */
    private fun ensureLaneSession(cwd: String, lane: String): String {
        val existing = state.lanes.firstOrNull { it.cwd == cwd && it.name == lane }
        existing?.sessionId?.takeIf { it.isNotEmpty() }?.let { return it }

        val sessionId = UUID.randomUUID().toString()
        if (existing != null) existing.sessionId = sessionId
        else state.lanes += LaneEntry(lane, cwd, sessionId)
        return sessionId
    }

    /** TODO → QUEUED. 순서가 오면 실행된다 */
    fun promote(id: String) {
        val task = find(id) ?: return
        if (task.status != TaskStatus.TODO && !task.status.isFinished) return
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
    fun runAllTodos() {
        val ids = todos().map { it.id }
        ids.forEach { id ->
            find(id)?.let { it.status = TaskStatus.QUEUED }
        }
        if (ids.isNotEmpty()) notifyChanged()
        maybeStartNext()
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
        finish(task, TaskStatus.CANCELED, exitCode = null, errorMessage = "사용자 취소")
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
        // 레인 소속이면 그 레인의 세션을 이어 쓴다. 독립 작업은 러너가 새로 만든다
        if (task.lane.isNotEmpty()) {
            task.sessionId = ensureLaneSession(task.cwd, task.lane)
        }

        task.status = TaskStatus.RUNNING
        task.startedAt = clock()
        task.attempts += 1
        runningId = task.id
        synchronized(logBuffer) { logBuffer.clear() }
        notifyChanged()

        running = launcher.launch(
            task = task,
            onLine = { line -> appendLog(line) },
            onText = { text -> collectRefs(task, text) },
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
        task.errorMessage = "사용자 인터럽트로 중단"
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

        val failed = result.exitCode != 0 || result.errorMessage != null
        finish(
            task = task,
            status = if (failed) TaskStatus.FAILED else TaskStatus.DONE,
            exitCode = result.exitCode,
            errorMessage = result.errorMessage ?: if (failed) "exit ${result.exitCode}" else null,
        )
        maybeStartNext()
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
        private const val MAX_LOG_LINES = 500

        fun getInstance(): TaskQueueService = service()
    }
}
