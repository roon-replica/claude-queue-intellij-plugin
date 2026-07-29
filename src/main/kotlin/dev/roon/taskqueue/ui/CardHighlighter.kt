package dev.roon.taskqueue.ui

import dev.roon.taskqueue.queue.TaskStatus
import javax.swing.Timer

/**
 * 상태가 바뀐 카드를 잠깐 물들인다.
 *
 * 카드가 소리 없이 다른 컬럼으로 순간이동하면 무엇이 어디로 갔는지 놓친다.
 * 짧은 잔상으로 시선을 끌어준다.
 */
class CardHighlighter(private val onRepaint: () -> Unit) {

    /** taskId → 하이라이트 시작 시각 */
    private val started = mutableMapOf<String, Long>()

    /** taskId → 도착한 상태 (색을 다르게 쓴다) */
    private val arrived = mutableMapOf<String, TaskStatus>()

    private val timer = Timer(FRAME_MS) { tick() }

    /** 이전 스냅샷 — 상태가 실제로 바뀐 것만 물들이기 위해 */
    private val lastStatus = mutableMapOf<String, TaskStatus>()

    /**
     * 큐가 갱신될 때마다 호출한다. 상태가 달라진 항목을 찾아 하이라이트를 건다.
     * @return 이번에 새로 상태가 바뀐 taskId 들 (스크롤 대상)
     */
    fun onTasksChanged(tasks: List<Pair<String, TaskStatus>>): Set<String> {
        val now = System.currentTimeMillis()
        val changed = mutableSetOf<String>()

        tasks.forEach { (id, status) ->
            val previous = lastStatus[id]
            // 처음 본 항목은 '바뀐 것' 이 아니다 — IDE 재시작 때 전부 번쩍이면 안 된다
            if (previous != null && previous != status) {
                started[id] = now
                arrived[id] = status
                changed += id
            }
            lastStatus[id] = status
        }

        // 사라진 항목 정리
        val alive = tasks.map { it.first }.toSet()
        lastStatus.keys.retainAll(alive)
        started.keys.retainAll(alive)
        arrived.keys.retainAll(alive)

        if (started.isNotEmpty() && !timer.isRunning) timer.start()
        return changed
    }

    /**
     * 0f = 하이라이트 없음, 1f = 가장 진함.
     * 확 물들었다가 서서히 빠진다 — 깜빡임보다 차분하고 시선을 덜 어지럽힌다.
     */
    fun strength(taskId: String): Float {
        val at = started[taskId] ?: return 0f
        val elapsed = System.currentTimeMillis() - at
        if (elapsed >= DURATION_MS) return 0f
        return 1f - (elapsed.toFloat() / DURATION_MS)
    }

    fun arrivedStatus(taskId: String): TaskStatus? = arrived[taskId]

    fun stop() = timer.stop()

    private fun tick() {
        val now = System.currentTimeMillis()
        started.entries.removeAll { now - it.value >= DURATION_MS }
        if (started.isEmpty()) timer.stop()
        onRepaint()
    }

    private companion object {
        const val FRAME_MS = 30
        const val DURATION_MS = 900L
    }
}
