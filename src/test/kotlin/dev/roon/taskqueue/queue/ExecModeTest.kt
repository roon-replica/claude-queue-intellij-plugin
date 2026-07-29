package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 실행 방식에 따라 러너가 갈리는지 */
class ExecModeTest {

    private class Recorder(val name: String) : TaskLauncher {
        val launched = mutableListOf<String>()
        override fun launch(
            task: TaskEntry,
            onLine: (String) -> Unit,
            onState: (SessionState) -> Unit,
            onDone: (TaskResult) -> Unit,
        ): RunningTask {
            launched += task.prompt
            return object : RunningTask {
                override fun cancel() = Unit
            }
        }
    }

    @Test
    fun `기본은 터미널 모드`() {
        assertEquals(ExecMode.TERMINAL, TaskEntry().execMode)
    }

    @Test
    fun `모드에 따라 러너가 갈린다`() {
        val head = Recorder("head")
        val term = Recorder("term")
        val queue = TaskQueueService().apply {
            launcher = head
            terminalLauncher = term
            clock = { 1L }
            pause()
        }

        val a = queue.addTodo("터미널작업", "/tmp", execMode = ExecMode.TERMINAL)
        val b = queue.addTodo("백그라운드작업", "/tmp", execMode = ExecMode.HEADLESS)
        queue.start()
        queue.promote(a.id)

        assertEquals(listOf("터미널작업"), term.launched)
        assertEquals(emptyList(), head.launched)

        // 터미널 작업 하나가 도는 중이라 b 는 대기 — 취소로 자리를 비우고 확인
        queue.cancelRunning()
        queue.promote(b.id)
        assertEquals(listOf("백그라운드작업"), head.launched)
    }

    @Test
    fun `터미널 러너가 없으면 헤드리스로 폴백`() {
        val head = Recorder("head")
        val queue = TaskQueueService().apply {
            launcher = head
            terminalLauncher = null
            clock = { 1L }
        }
        val t = queue.addTodo("작업", "/tmp", execMode = ExecMode.TERMINAL)
        queue.promote(t.id)
        assertEquals(listOf("작업"), head.launched)
    }
}
