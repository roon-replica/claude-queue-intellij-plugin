package dev.roon.taskqueue.terminal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmitDelayTest {

    @Test
    fun `짧은 입력은 기본 간격에 가깝다`() {
        assertTrue(SubmitDelay.forText("hi") < 200, "${SubmitDelay.forText("hi")}")
    }

    /** 큰 본문은 터미널이 삼키는 데 더 걸린다 — 간격이 늘어야 한다 */
    @Test
    fun `큰 입력은 간격이 늘어난다`() {
        val small = SubmitDelay.forText("a".repeat(10))
        val large = SubmitDelay.forText("a".repeat(4_000))
        assertTrue(large > small, "$large vs $small")
    }

    /** 한글은 글자당 3바이트 — 글자 수가 아니라 바이트로 재야 한다 */
    @Test
    fun `한글은 같은 글자 수의 영문보다 간격이 길다`() {
        assertTrue(SubmitDelay.forText("가".repeat(100)) > SubmitDelay.forText("a".repeat(100)))
    }

    /** 아무리 커도 한없이 기다리면 안 된다 */
    @Test
    fun `간격에는 상한이 있다`() {
        assertEquals(1_500, SubmitDelay.forText("a".repeat(1_000_000)))
    }

    @Test
    fun `기다린 뒤 Enter 를 보낸다`() {
        val sent = mutableListOf<String>()
        SubmitDelay.submitAfter("hi") { sent += it }
        // 예약만 하고 즉시 보내지 않는다 — 본문이 들어갈 시간을 준다
        assertTrue(sent.isEmpty())

        Thread.sleep(SubmitDelay.forText("hi") + 500)
        assertEquals(listOf("\r"), sent)
    }
}
