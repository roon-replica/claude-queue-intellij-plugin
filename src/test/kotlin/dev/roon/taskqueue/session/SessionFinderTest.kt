package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 프롬프트로 세션을 특정하는 규칙. 실제 판정 함수를 그대로 쓴다 */
class SessionFinderTest {

    @TempDir
    lateinit var tmp: Path

    private fun userLine(text: String) =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${quote(text)}}]}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun file(name: String, vararg lines: String): File =
        File(tmp.toFile(), name).apply { writeText(lines.joinToString("\n")) }

    @Test
    fun `보낸 프롬프트가 든 세션을 알아본다`() {
        val f = file("a.jsonl", userLine("다른 작업"), userLine("로그인 버그 수정해줘"))
        assertTrue(SessionFinder.containsUserPrompt(f, "로그인 버그 수정해줘"))
    }

    @Test
    fun `없는 프롬프트는 알아보지 않는다`() {
        val f = file("b.jsonl", userLine("다른 작업"))
        assertFalse(SessionFinder.containsUserPrompt(f, "로그인 버그 수정해줘"))
    }

    @Test
    fun `공백 차이는 무시한다`() {
        val needle = SessionFinder.normalize("로그인   버그\n수정해줘")
        assertTrue(SessionFinder.matches(userLine("로그인 버그 수정해줘"), needle))
    }

    @Test
    fun `assistant 항목은 매칭하지 않는다`() {
        val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"로그인 버그 수정해줘"}]}}"""
        assertFalse(SessionFinder.matches(line, "로그인 버그 수정해줘"))
    }

    @Test
    fun `프롬프트가 비면 찾지 않는다`() {
        assertNull(SessionFinder.findByPrompt(tmp.toString(), "   ", 0))
    }
}
