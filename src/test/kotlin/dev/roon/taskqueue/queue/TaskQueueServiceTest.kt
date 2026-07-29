package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 큐 로직 검증 — 실행은 FakeLauncher 로 대체(플랫폼 무의존) */
class TaskQueueServiceTest {

    /** launch 호출을 기록하고, 완료 시점을 테스트가 직접 정한다 */
    private class FakeLauncher : TaskLauncher {
        val launched = mutableListOf<TaskEntry>()
        var canceled = 0
        private var pending: ((TaskResult) -> Unit)? = null
        private var pendingState: ((SessionState) -> Unit)? = null

        override fun launch(
            task: TaskEntry,
            onLine: (String) -> Unit,
            onState: (SessionState) -> Unit,
            onDone: (TaskResult) -> Unit,
        ): RunningTask {
            launched += task
            pending = onDone
            pendingState = onState
            return object : RunningTask {
                override fun cancel() {
                    canceled++
                }
            }
        }

        fun emitState(state: SessionState) = pendingState?.invoke(state)

        fun complete(exitCode: Int = 0, state: SessionState = SessionState.DONE, error: String? = null) {
            val done = pending ?: error("실행 중인 작업 없음")
            pending = null
            done(TaskResult(exitCode, state, 0.01, error))
        }
    }

    private lateinit var queue: TaskQueueService
    private lateinit var fake: FakeLauncher
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        fake = FakeLauncher()
        queue = TaskQueueService().apply {
            launcher = fake
            clock = { now }
        }
    }

    @Test
    fun `enqueue 하면 즉시 첫 작업이 실행된다`() {
        queue.enqueue("작업1", "/tmp")
        assertEquals(1, fake.launched.size)
        assertEquals(TaskStatus.RUNNING, queue.tasks[0].status)
    }

    @Test
    fun `두 번째 작업은 첫 작업이 끝난 뒤 실행된다`() {
        queue.enqueue("작업1", "/tmp")
        queue.enqueue("작업2", "/tmp")

        assertEquals(1, fake.launched.size) // 순차 — 아직 1건만
        assertEquals(TaskStatus.QUEUED, queue.tasks[1].status)

        fake.complete()
        assertEquals(2, fake.launched.size)
        assertEquals(TaskStatus.DONE, queue.tasks[0].status)
        assertEquals(TaskStatus.RUNNING, queue.tasks[1].status)
    }

    @Test
    fun `exit 0 이면 DONE`() {
        queue.enqueue("작업", "/tmp")
        fake.complete(exitCode = 0)
        assertEquals(TaskStatus.DONE, queue.tasks[0].status)
        assertNull(queue.tasks[0].errorMessage)
    }

    @Test
    fun `exit 0 이 아니면 FAILED`() {
        queue.enqueue("작업", "/tmp")
        fake.complete(exitCode = 1)
        assertEquals(TaskStatus.FAILED, queue.tasks[0].status)
        assertEquals("exit 1", queue.tasks[0].errorMessage)
    }

    @Test
    fun `exit 0 이라도 result 오류면 FAILED`() {
        queue.enqueue("작업", "/tmp")
        fake.complete(exitCode = 0, error = "API 오류")
        assertEquals(TaskStatus.FAILED, queue.tasks[0].status)
        assertEquals("API 오류", queue.tasks[0].errorMessage)
    }

    @Test
    fun `실패 1건이 큐 전체를 멈추지 않는다`() {
        queue.enqueue("작업1", "/tmp")
        queue.enqueue("작업2", "/tmp")
        fake.complete(exitCode = 1)
        assertEquals(TaskStatus.FAILED, queue.tasks[0].status)
        assertEquals(TaskStatus.RUNNING, queue.tasks[1].status)
    }

    @Test
    fun `retry 는 다시 큐에 올리고 세션 ID 를 유지한다`() {
        queue.enqueue("작업", "/tmp")
        queue.tasks[0].sessionId = "sess-1"
        fake.complete(exitCode = 1)

        queue.retry(queue.tasks[0].id)
        assertEquals(TaskStatus.RUNNING, queue.tasks[0].status)
        assertEquals("sess-1", queue.tasks[0].sessionId)
        assertEquals(2, queue.tasks[0].attempts)
        assertNull(queue.tasks[0].errorMessage)
    }

    @Test
    fun `취소하면 CANCELED 로 남고 러너에 취소가 전달된다`() {
        queue.enqueue("작업", "/tmp")
        queue.cancelRunning()
        assertEquals(TaskStatus.CANCELED, queue.tasks[0].status)
        assertEquals(1, fake.canceled)
    }

    @Test
    fun `취소 후 다음 작업은 start 로 이어간다`() {
        queue.enqueue("작업1", "/tmp")
        queue.enqueue("작업2", "/tmp")
        queue.cancelRunning()
        queue.start()
        assertEquals(TaskStatus.RUNNING, queue.tasks[1].status)
    }

    @Test
    fun `pause 중에는 다음 작업을 시작하지 않는다`() {
        queue.enqueue("작업1", "/tmp")
        queue.enqueue("작업2", "/tmp")
        queue.pause()
        fake.complete()
        assertEquals(TaskStatus.QUEUED, queue.tasks[1].status)
        assertEquals(1, fake.launched.size)
    }

    @Test
    fun `순서 이동`() {
        queue.pause()
        val a = queue.enqueue("A", "/tmp")
        val b = queue.enqueue("B", "/tmp")
        queue.move(b.id, -1)
        assertEquals(listOf(b.id, a.id), queue.tasks.map { it.id })
    }

    @Test
    fun `순서 이동은 범위를 벗어나지 않는다`() {
        queue.pause()
        val a = queue.enqueue("A", "/tmp")
        queue.enqueue("B", "/tmp")
        queue.move(a.id, -5)
        assertEquals("A", queue.tasks[0].prompt)
    }

    @Test
    fun `완료 항목 정리`() {
        queue.enqueue("작업1", "/tmp")
        fake.complete()
        queue.enqueue("작업2", "/tmp")
        queue.clearFinished()
        assertEquals(1, queue.tasks.size)
        assertEquals("작업2", queue.tasks[0].prompt)
    }

    @Test
    fun `실행 중 상태 전이가 항목에 기록된다`() {
        queue.enqueue("작업", "/tmp")
        fake.emitState(SessionState.WORKING)
        assertEquals(SessionState.WORKING, queue.tasks[0].finalState)
        fake.complete(state = SessionState.DONE)
        assertEquals(SessionState.DONE, queue.tasks[0].finalState)
    }

    @Test
    fun `IDE 재시작 시 RUNNING 은 QUEUED 로 복원된다`() {
        queue.enqueue("작업", "/tmp")
        val saved = queue.getState()
        assertEquals(TaskStatus.RUNNING, saved.tasks[0].status)

        // 새 인스턴스에 로드 = IDE 재시작
        val restored = TaskQueueService().apply {
            launcher = FakeLauncher()
            clock = { now }
            autoAdvance = false
        }
        restored.loadState(saved)

        assertEquals(TaskStatus.QUEUED, restored.tasks[0].status)
        assertNull(restored.tasks[0].startedAt)
        assertNull(restored.runningTask())
    }

    @Test
    fun `리스너가 변경마다 호출된다`() {
        var count = 0
        queue.addListener { count++ }
        queue.enqueue("작업", "/tmp")
        assertTrue(count >= 2) // 추가 + 실행 시작
    }

    @Test
    fun `remove 는 실행 중이면 취소 후 제거한다`() {
        val t = queue.enqueue("작업", "/tmp")
        queue.remove(t.id)
        assertEquals(0, queue.tasks.size)
        assertEquals(1, fake.canceled)
    }
}
