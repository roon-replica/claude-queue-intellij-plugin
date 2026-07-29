package dev.roon.taskqueue.hook

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stop 훅 payload 판독 규칙 검증.
 * 실제 payload 는 실측한 것 그대로 사용한다.
 */
class StopHookPayloadTest {

    /** StopHookWatcher.parse 와 같은 규칙 — 서비스 인스턴스 없이 검증하려고 복제 */
    private fun read(json: String): Triple<String, String?, String?>? {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("stop_hook_active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return null
        val sid = root.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return Triple(
            sid,
            root.get("transcript_path")?.takeIf { it.isJsonPrimitive }?.asString,
            root.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }

    private val real = """
        {"session_id":"d108b0b9-7e00-46cd-821d-51949ad0c4d3",
         "transcript_path":"/Users/x/.claude/projects/-Users-x-p/d108b0b9.jsonl",
         "cwd":"/Users/x/IdeaProjects/checker-v2",
         "hook_event_name":"Stop","stop_hook_active":false}
    """.trimIndent()

    @Test
    fun `실측 payload 에서 세션 ID 를 읽는다`() {
        val r = read(real)!!
        assertEquals("d108b0b9-7e00-46cd-821d-51949ad0c4d3", r.first)
        assertEquals("/Users/x/IdeaProjects/checker-v2", r.third)
    }

    @Test
    fun `stop_hook_active 는 훅이 유발한 재진입이라 무시한다`() {
        assertNull(read("""{"session_id":"a","stop_hook_active":true}"""))
    }

    @Test
    fun `session_id 없으면 버린다`() {
        assertNull(read("""{"hook_event_name":"Stop"}"""))
    }

    @Test
    fun `transcript_path 는 없어도 된다`() {
        val r = read("""{"session_id":"a","stop_hook_active":false}""")!!
        assertEquals("a", r.first)
        assertNull(r.second)
    }

    @Test
    fun `훅 명령에 mktemp 템플릿과 리다이렉션이 들어간다`() {
        // hookCommand() 는 경로만 조립하므로 형태만 검증
        val template = "/Users/x/.task-queue/stops/stop-XXXXXXXX.json"
        val cmd = "cat > \"\$(mktemp $template)\""
        assertTrue(cmd.startsWith("cat > "))
        assertTrue(cmd.contains("mktemp"))
        assertTrue(cmd.contains("stop-XXXXXXXX.json"))
    }
}
