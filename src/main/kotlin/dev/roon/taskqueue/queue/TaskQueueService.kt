package dev.roon.taskqueue.queue

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
 * 자동작업 큐. **프로젝트마다 하나**다 — 한 프로젝트 안에서는 1건씩 순차로 돌고,
 * 프로젝트가 여럿이면 각자 하나씩 병렬로 돈다. A 를 돌려놓고 B 를 보는 게 그래서 된다.
 *
 * 상태는 `.idea/workspace.xml` 에 둔다. 전용 파일로 두면 VCS 에 올라가 팀원에게
 * 내 작업 목록이 보이는데, 큐는 개인 것이다.
 */
@Service(Service.Level.PROJECT)
@State(name = "TaskQueue", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TaskQueueService : PersistentStateComponent<TaskQueueState> {

    private var state = TaskQueueState()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val draftListeners = CopyOnWriteArrayList<(String) -> Unit>()

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

    /** 터미널 탭이 아직 살아있는지 (테스트에서 교체) */
    var tabExists: (String) -> Boolean =
        { label -> dev.roon.taskqueue.terminal.TerminalSessionRegistry.getInstance().find(label) != null }

    /** 시간 주입 — 테스트 결정성 확보 */
    var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * 방(터미널 탭)마다 도는 작업. **방 하나에 하나** — 방 사이는 병렬, 방 안은 순차다.
     * 키는 [roomOf], 값은 그 방에서 도는 작업 id 와 러너 핸들.
     */
    private val running = mutableMapOf<String, Run>()

    private class Run(val taskId: String, val handle: RunningTask)

    /** 자동 진행 여부. false 면 한 건 끝나도 다음을 시작하지 않는다 */
    var autoAdvance: Boolean = true

    // --- 조회 ---

    val tasks: List<TaskEntry> get() = state.tasks.toList()

    fun todos(): List<TaskEntry> = state.tasks.filter { it.status == TaskStatus.TODO }

    fun queued(): List<TaskEntry> = state.tasks.filter { it.status == TaskStatus.QUEUED }

    /** 지금 도는 작업들 (방마다 최대 하나) */
    fun runningTasks(): List<TaskEntry> =
        running.values.mapNotNull { run -> state.tasks.find { it.id == run.taskId } }

    /** 도는 게 하나라도 있는지 — 툴바 활성 판정 등 */
    fun runningTask(): TaskEntry? = runningTasks().firstOrNull()

    /**
     * 작업이 속한 방. **탭이 곧 방**이고, 탭이 아직 없으면 묶음으로, 그것도 없으면
     * 공용 '새 대화' 방으로 묶는다 — 탭 없는 작업마다 방을 만들면 TODO 를 올릴 때
     * claude 세션이 그 수만큼 동시에 뜬다.
     */
    fun roomOf(task: TaskEntry): String = when {
        task.terminalTab.isNotEmpty() -> task.terminalTab
        task.batchId != null -> "batch:${task.batchId}"
        else -> NEW_ROOM
    }

    /** 그 방에서 도는 작업 */
    fun runningIn(room: String): TaskEntry? =
        running[room]?.let { run -> state.tasks.find { it.id == run.taskId } }

    /** 화면에 세울 방 목록 — 대기·실행 중인 작업이 있는 방만, 처음 등장한 순서로 */
    fun activeRooms(): List<String> =
        state.tasks.filter { it.status.isActive }.map(::roomOf).distinct()

    fun find(id: String): TaskEntry? = state.tasks.find { it.id == id }

    // --- 변경 ---

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 입력창에 채워 넣을 초안을 전한다 (에디터 선택 → 작업 추가).
     *
     * 액션이 패널을 직접 찾지 않게 서비스를 거친다. **저장하지 않는 일회성 전달**이다 —
     * 툴윈도가 닫혀 있으면 activate 하면서 패널이 생겨 구독하므로 그 뒤에 부르면 된다.
     */
    fun proposeDraft(text: String) {
        val draft = text.trim()
        if (draft.isEmpty()) return
        draftListeners.forEach { runCatching { it(draft) } }
    }

    fun addDraftListener(listener: (String) -> Unit) {
        draftListeners += listener
    }

    fun removeDraftListener(listener: (String) -> Unit) {
        draftListeners -= listener
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

    /**
     * TODO 전부를 대기줄로 올린다.
     *
     * "새 대화"(빈 탭 이름)로 올리면 **한 묶음으로 묶는다** — 첫 작업이 연 탭을 나머지가
     * 물려받아 같은 대화방에서 직렬로 돈다. 각각 새 탭을 열면 맥락이 끊기고 탭만 쌓인다.
     */
    fun runAllTodos(terminalTab: String? = null) {
        val ids = todos().map { it.id }
        val batch = if (terminalTab?.isEmpty() == true && ids.size > 1) UUID.randomUUID().toString() else null
        ids.forEach { id ->
            find(id)?.let {
                terminalTab?.let { tab -> it.terminalTab = tab }
                it.batchId = batch
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
        cancel(id)
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

    /**
     * 실행 중 작업을 **같은 세션에 다시 보낸다.**
     *
     * 사용자가 터미널에서 Esc 로 끊으면 Stop 훅이 오지 않아 작업이 타임아웃까지 실행중으로
     * 남는다. 그때 사람이 직접 다시 트리거하는 통로다. 탭과 세션 ID 를 그대로 두므로
     * 인터럽트 시점까지의 맥락이 살아있는 그 대화에 프롬프트가 다시 들어간다.
     */
    fun resend(id: String) {
        val task = find(id) ?: return
        if (task.status != TaskStatus.RUNNING) return
        // 옛 구독을 먼저 뗀다 — 안 떼면 지난 턴의 Stop 신호가 이 재전송의 완료로 읽힌다
        detach(task)
        startTask(task)
    }

    /**
     * 대기 중(QUEUED) 항목을 지금 실행한다.
     *
     * 실행 중 항목을 취소·삭제하면 큐가 다음으로 넘어가지 않아(취소 경로는 [maybeStartNext] 를
     * 부르지 않는다) 대기 항목이 그대로 남는다 — 그때 사람이 직접 돌리는 통로다.
     *
     * **[autoAdvance] 는 건드리지 않는다.** 일시정지 상태에서 눌렀다면 이것 하나만 돌아야 하고,
     * 정지가 아니라면 끝난 뒤 자연히 다음으로 이어진다.
     */
    fun runNow(id: String) {
        val task = find(id) ?: return
        if (task.status != TaskStatus.QUEUED) return
        // 방 안에서는 순차 — 그 방이 비어 있어야 시작한다
        if (running.containsKey(roomOf(task))) return
        startTask(task)
    }

    /** 그 작업을 취소한다 — 같은 방의 다른 항목만 영향받고 다른 방은 그대로 돈다 */
    fun cancel(id: String) {
        val task = find(id)?.takeIf { it.status == TaskStatus.RUNNING } ?: return
        detach(task)
        finish(task, TaskStatus.CANCELED, exitCode = null, errorMessage = "Canceled by user")
    }

    /** 도는 것 전부 취소 */
    fun cancelRunning() = runningTasks().forEach { cancel(it.id) }

    /**
     * 대기 항목을 다른 방으로 옮긴다 (컬럼 사이 드래그).
     * 옮긴 방이 비어 있으면 곧바로 시작될 수 있다 — 방 사이는 병렬이다.
     *
     * **실행 중 항목은 옮기지 않는다** — 이미 그 탭의 claude 가 물고 있다.
     * 묶음도 푼다: 다른 탭으로 갔는데 묶음 규칙이 옛 탭을 물려주면 안 된다.
     */
    fun moveToRoom(id: String, terminalTab: String) {
        val task = find(id)?.takeIf { it.status == TaskStatus.QUEUED } ?: return
        if (task.terminalTab == terminalTab) return
        task.terminalTab = terminalTab
        task.batchId = null
        notifyChanged()
        maybeStartNext()
    }

    /** 그 작업의 구독을 뗀다 — 방을 비워 다음 항목이 들어올 수 있게 */
    private fun detach(task: TaskEntry) {
        val room = running.entries.firstOrNull { it.value.taskId == task.id }?.key ?: return
        running.remove(room)?.handle?.cancel()
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

    /** 비어 있는 방마다 그 방의 첫 대기 항목을 시작한다 — 방 사이는 병렬이다 */
    private fun maybeStartNext() {
        if (!autoAdvance) return
        // 시작하면서 목록이 바뀌므로 스냅샷을 뜨고 돈다
        val rooms = queued().map(::roomOf).distinct()
        for (room in rooms) {
            if (running.containsKey(room)) continue
            val next = queued().firstOrNull { roomOf(it) == room } ?: continue
            inheritBatchTab(next)
            startTask(next)
        }
    }

    /**
     * 같은 묶음의 앞선 작업이 연 탭을 물려받는다.
     * 시작 직전에 보므로 앞 작업이 이미 끝나 탭 이름이 채워진 상태다 —
     * 런처가 새 탭을 만들 때 [TaskEntry.terminalTab] 에 그 이름을 써 둔다.
     */
    private fun inheritBatchTab(task: TaskEntry) {
        if (task.terminalTab.isNotEmpty()) return
        val batch = task.batchId ?: return
        val opened = state.tasks.firstOrNull { it.batchId == batch && it.terminalTab.isNotEmpty() } ?: return
        // 그 탭을 사람이 닫았을 수도 있다 — 없으면 물려받지 않고 새 탭을 연다
        if (!tabExists(opened.terminalTab)) return
        task.terminalTab = opened.terminalTab
    }

    private fun startTask(task: TaskEntry) {
        task.status = TaskStatus.RUNNING
        task.startedAt = clock()
        task.attempts += 1
        val room = roomOf(task)
        notifyChanged()

        // 방이 여럿이면 로그가 뒤섞인다 — 어느 방 줄인지 붙인다
        val prefix = if (room == NEW_ROOM) "" else "[$room] "
        val handle = launcherFor(task).launch(
            task = task,
            onLine = { line -> appendLog(prefix + line) },
            onState = { state -> onRunningState(task, state) },
            onDone = { result -> onTaskDone(task, result) },
        )
        // 런처가 즉시 끝내면(실패) onDone 이 먼저 와 방을 비운다 — 그 뒤에 덮어쓰지 않는다
        if (find(task.id)?.status == TaskStatus.RUNNING) running[room] = Run(task.id, handle)
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
        detach(task)
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
        // 이 방을 비운다 — 핸들은 이미 끝났으므로 cancel 하지 않는다
        running.entries.removeAll { it.value.taskId == task.id }
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
        // 모든 방이 비었을 때가 큐가 다 끝난 시점 — 자리를 비운 사람에게 요약을 보낸다
        if (running.isEmpty()) notifyQueueDrained(task.cwd)
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
        running.clear()
    }

    companion object {
        /** 탭도 묶음도 없는 작업이 모이는 공용 방 — 저마다 방을 만들면 세션이 폭주한다 */
        const val NEW_ROOM = "new"

        private const val MAX_HISTORY = 20

        private const val MAX_LOG_LINES = 500

        fun getInstance(project: Project): TaskQueueService = project.service()
    }
}
