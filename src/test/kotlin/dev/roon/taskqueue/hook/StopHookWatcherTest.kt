package dev.roon.taskqueue.hook

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopHookWatcherTest {

    private val watcher = StopHookWatcher()

    private fun signalFile(sessionId: String): File =
        File(watcher.stopsDir, watcher.fileName(sessionId)).also {
            it.parentFile.mkdirs()
            it.writeText("""{"session_id":"$sessionId","hook_event_name":"Stop"}""")
        }

    /**
     * 앞 턴이 남긴 신호가 있으면 프롬프트를 보내는 순간 완료로 오판된다 —
     * 파일명이 세션 ID 로 고정이라 턴마다 같은 경로를 쓰기 때문이다.
     */
    @Test
    fun `discardPending 은 그 세션의 남은 신호를 지운다`() {
        val sessionId = "11111111-2222-3333-4444-555555555555"
        val file = signalFile(sessionId)
        assertTrue(file.isFile)

        watcher.discardPending(sessionId)

        assertFalse(file.exists())
    }

    /** 남의 세션 신호까지 지우면 그 작업의 완료를 놓친다 */
    @Test
    fun `discardPending 은 다른 세션 신호는 남긴다`() {
        val mine = "aaaaaaaa-0000-0000-0000-000000000000"
        val other = "bbbbbbbb-0000-0000-0000-000000000000"
        val mineFile = signalFile(mine)
        val otherFile = signalFile(other)

        watcher.discardPending(mine)

        assertFalse(mineFile.exists())
        assertTrue(otherFile.isFile)
        otherFile.delete()
    }

    @Test
    fun `신호가 없어도 조용히 넘어간다`() {
        watcher.discardPending("cccccccc-0000-0000-0000-000000000000")
    }

    /** 훅 명령과 삭제가 같은 경로를 봐야 한다 — 어긋나면 삭제가 헛돈다 */
    @Test
    fun `훅 명령의 경로와 신호 파일명이 일치한다`() {
        val sessionId = "dddddddd-0000-0000-0000-000000000000"
        assertTrue(watcher.hookCommand(sessionId).contains(watcher.fileName(sessionId)))
    }
}
