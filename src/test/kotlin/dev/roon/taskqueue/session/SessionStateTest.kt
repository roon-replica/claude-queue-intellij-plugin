package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStateTest {

    /** 답변 중에 보내면 두 요청이 겹치고, 끝나는 신호를 우리 것으로 오판한다 */
    @Test
    fun `답변 중은 보내면 안 되는 상태`() {
        assertTrue(SessionState.WORKING.isBusy)
    }

    /** 질문 대기 중에 보내면 우리 프롬프트가 그 질문의 답이 된다 */
    @Test
    fun `질문 대기도 보내면 안 되는 상태`() {
        assertTrue(SessionState.WAITING.isBusy)
    }

    @Test
    fun `끝난 상태들은 보내도 된다`() {
        assertFalse(SessionState.DONE.isBusy)
        assertFalse(SessionState.IDLE.isBusy)
    }

    /** 판단 불가에서 막으면 큐가 영원히 멈춘다 — 진행하는 쪽으로 둔다 */
    @Test
    fun `판단 불가는 막지 않는다`() {
        assertFalse(SessionState.UNKNOWN.isBusy)
    }

    @Test
    fun `isTerminal 과 isBusy 는 겹치지 않는다`() {
        SessionState.entries.forEach { state ->
            assertFalse(state.isTerminal && state.isBusy, "$state")
        }
    }
}
