package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentSessionsTest {

    private fun entry(tokens: Long, model: String = "claude-opus-5", title: String = "제목") =
        RecentSessions.Entry("abc12345-0000-0000-0000-000000000000", title, 0, tokens, model)

    @Test
    fun `퍼센트는 1M 모델 기준으로 계산된다`() {
        assertEquals(13, entry(130_000).percent)
    }

    /** 0% 와 '모름' 은 다르다 — 모르면 아무것도 표시하지 않아야 한다 */
    @Test
    fun `토큰을 모르면 퍼센트는 null`() {
        assertNull(entry(0).percent)
    }

    @Test
    fun `미상 모델은 200K 기준`() {
        assertEquals(50, entry(100_000, model = "who-knows").percent)
    }

    @Test
    fun `사용량 라벨은 분모와 퍼센트를 함께 보여준다`() {
        assertEquals("130k/1m (13%)", entry(130_000).usageLabel())
    }

    /** 제목 없는 세션도 목록에서 고를 수 있어야 한다 */
    @Test
    fun `제목이 없으면 세션 ID 앞자리로 대체한다`() {
        val shown = entry(1, title = "").displayTitle()
        assertTrue(shown.contains("abc12345"), shown)
    }

    @Test
    fun `제목이 있으면 그대로 쓴다`() {
        assertEquals("제목", entry(1).displayTitle())
    }
}
