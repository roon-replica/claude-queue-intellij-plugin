package dev.roon.taskqueue.session

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContextUsageTest {

    private fun usage(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `점유 토큰은 input + 캐시생성 + 캐시읽기`() {
        val u = usage("""{"input_tokens":2,"cache_creation_input_tokens":10874,"cache_read_input_tokens":15738,"output_tokens":8}""")
        assertEquals(26614, ContextUsage.usageTokens(u))
    }

    @Test
    fun `usage 없으면 0`() {
        assertEquals(0, ContextUsage.usageTokens(null))
    }

    @Test
    fun `누락 필드는 0 으로 취급`() {
        assertEquals(5, ContextUsage.usageTokens(usage("""{"input_tokens":5}""")))
    }

    @Test
    fun `opus-5 는 1M 한도`() {
        assertEquals(1_000_000, ContextUsage.contextLimit("claude-opus-5"))
    }

    @Test
    fun `미상 모델은 200K 한도`() {
        assertEquals(200_000, ContextUsage.contextLimit("some-unknown-model"))
    }

    @Test
    fun `미상 모델이 한도를 넘기면 올려 잡는다`() {
        assertEquals(1_000_000, ContextUsage.contextLimit("unknown", 300_000))
    }

    @Test
    fun `토큰 포맷`() {
        assertEquals("900", ContextUsage.formatTokens(900))
        assertEquals("130.3k", ContextUsage.formatTokens(130_300))
        assertEquals("200k", ContextUsage.formatTokens(200_000))
        assertEquals("1m", ContextUsage.formatTokens(1_000_000))
        assertEquals("1.2m", ContextUsage.formatTokens(1_200_000))
    }

    @Test
    fun `퍼센트는 100 을 넘지 않는다`() {
        assertEquals(13, ContextUsage.percent(130_000, 1_000_000))
        assertEquals(100, ContextUsage.percent(2_000_000, 1_000_000))
        assertEquals(0, ContextUsage.percent(0, 1_000_000))
    }

    @Test
    fun `경고 레벨 경계`() {
        assertEquals(ContextUsage.Level.OK, ContextUsage.level(59))
        assertEquals(ContextUsage.Level.WARN, ContextUsage.level(60))
        assertEquals(ContextUsage.Level.HEAVY, ContextUsage.level(80))
    }

    @Test
    fun `라벨 형식`() {
        assertEquals("130.3k/1m (13%)", ContextUsage.label(130_300, "claude-opus-5"))
        assertEquals("", ContextUsage.label(0, "claude-opus-5"))
    }
}
