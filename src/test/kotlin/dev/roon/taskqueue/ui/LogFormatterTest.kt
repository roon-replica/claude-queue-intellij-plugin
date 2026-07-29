package dev.roon.taskqueue.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFormatterTest {

    @Test
    fun `훅 이벤트는 버린다`() {
        assertNull(LogFormatter.format("""{"type":"system","subtype":"hook_started","hook_name":"X"}"""))
        assertNull(LogFormatter.format("""{"type":"system","subtype":"hook_response"}"""))
    }

    @Test
    fun `init 은 모델을 보여준다`() {
        val out = LogFormatter.format("""{"type":"system","subtype":"init","model":"claude-opus-5"}""")
        assertEquals("▸ session started  (claude-opus-5)", out)
    }

    @Test
    fun `assistant 텍스트는 그대로`() {
        val out = LogFormatter.format("""{"type":"assistant","message":{"content":[{"type":"text","text":"고쳤어"}]}}""")
        assertEquals("고쳤어", out)
    }

    @Test
    fun `도구 호출은 이름과 인자 요약`() {
        val line = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/Foo.kt"}}]}}"""
        assertEquals("· Read(src/Foo.kt)", LogFormatter.format(line))
    }

    @Test
    fun `인자 없는 도구는 이름만`() {
        val line = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"TodoWrite","input":{}}]}}"""
        assertEquals("· TodoWrite", LogFormatter.format(line))
    }

    @Test
    fun `결과는 시간과 비용`() {
        val line = """{"type":"result","is_error":false,"duration_ms":3542,"total_cost_usd":0.116819}"""
        assertEquals("✔ done  3.5s  $0.117", LogFormatter.format(line))
    }

    @Test
    fun `오류 결과는 실패로 표시`() {
        val line = """{"type":"result","is_error":true,"duration_ms":1000,"total_cost_usd":0.01}"""
        assertTrue(LogFormatter.format(line)!!.startsWith("✘ failed"))
    }

    @Test
    fun `rate limit 은 상태만`() {
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""
        assertEquals("· rate limit: allowed", LogFormatter.format(line))
    }

    @Test
    fun `깨진 라인은 버린다`() {
        assertNull(LogFormatter.format("{깨진"))
        assertNull(LogFormatter.format(""))
    }
}
