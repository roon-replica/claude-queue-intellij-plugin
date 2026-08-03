package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals

class SessionScannerTest {

    private fun jsonl(vararg lines: String): File {
        val f = createTempFile(suffix = ".jsonl").toFile()
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    private fun assistant(stopReason: String?, content: String = """[{"type":"text","text":"hi"}]"""): String {
        val sr = stopReason?.let { "\"$it\"" } ?: "null"
        return """{"type":"assistant","message":{"model":"claude-opus-5","stop_reason":$sr,"content":$content}}"""
    }

    @Test
    fun `stop_reason 있으면 DONE`() {
        assertEquals(SessionState.DONE, SessionScanner.sessionState(jsonl(assistant("end_turn"))))
    }

    @Test
    fun `stop_reason null 이면 스트리밍 중 WORKING`() {
        assertEquals(SessionState.WORKING, SessionScanner.sessionState(jsonl(assistant(null))))
    }

    @Test
    fun `tool_use 는 WORKING`() {
        val content = """[{"type":"tool_use","name":"Bash","input":{}}]"""
        assertEquals(SessionState.WORKING, SessionScanner.sessionState(jsonl(assistant("tool_use", content))))
    }

    @Test
    fun `AskUserQuestion tool_use 는 WAITING`() {
        val content = """[{"type":"tool_use","name":"AskUserQuestion","input":{"questions":[{"question":"A?"}]}}]"""
        assertEquals(SessionState.WAITING, SessionScanner.sessionState(jsonl(assistant("tool_use", content))))
    }

    @Test
    fun `ExitPlanMode tool_use 는 WAITING`() {
        val content = """[{"type":"tool_use","name":"ExitPlanMode","input":{}}]"""
        assertEquals(SessionState.WAITING, SessionScanner.sessionState(jsonl(assistant("tool_use", content))))
    }

    @Test
    fun `user 프롬프트는 WORKING`() {
        val f = jsonl(assistant("end_turn"), """{"type":"user","message":{"content":"다음 작업"}}""")
        assertEquals(SessionState.WORKING, SessionScanner.sessionState(f))
    }

    @Test
    fun `인터럽트 마커는 IDLE`() {
        val f = jsonl("""{"type":"user","message":{"content":"[Request interrupted by user]"}}""")
        assertEquals(SessionState.IDLE, SessionScanner.sessionState(f))
    }

    @Test
    fun `인터럽트 마커가 블록 배열 안에 있어도 IDLE`() {
        val f = jsonl(
            """{"type":"user","message":{"content":[{"type":"text","text":"[Request interrupted by user] 중단"}]}}"""
        )
        assertEquals(SessionState.IDLE, SessionScanner.sessionState(f))
    }

    @Test
    fun `auto compact 경계는 WORKING`() {
        val f = jsonl("""{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto"}}""")
        assertEquals(SessionState.WORKING, SessionScanner.sessionState(f))
    }

    @Test
    fun `manual compact 경계는 DONE`() {
        val f = jsonl("""{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"manual"}}""")
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `compact 요약은 잡음이라 건너뛴다`() {
        val f = jsonl(assistant("end_turn"), """{"type":"user","isCompactSummary":true,"message":{"content":"요약"}}""")
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `로컬 슬래시 명령 에코는 잡음이라 건너뛴다`() {
        val f = jsonl(
            assistant("end_turn"),
            """{"type":"user","message":{"content":"<command-name>/context</command-name>"}}""",
            """{"type":"user","message":{"content":"<local-command-stdout>out</local-command-stdout>"}}""",
        )
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `isMeta user 는 잡음이라 건너뛴다`() {
        val f = jsonl(assistant("end_turn"), """{"type":"user","isMeta":true,"message":{"content":"meta"}}""")
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `메타 엔트리는 건너뛰고 계속 본다`() {
        val f = jsonl(
            assistant("end_turn"),
            """{"type":"ai-title","aiTitle":"제목"}""",
            """{"type":"last-prompt","lastPrompt":"프롬프트"}""",
        )
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `깨진 라인은 무시한다`() {
        val f = jsonl(assistant("end_turn"), "{깨진 json", "")
        assertEquals(SessionState.DONE, SessionScanner.sessionState(f))
    }

    @Test
    fun `빈 파일은 UNKNOWN`() {
        val f = createTempFile(suffix = ".jsonl").toFile()
        assertEquals(SessionState.UNKNOWN, SessionScanner.sessionState(f))
    }

    @Test
    fun `없는 파일은 UNKNOWN`() {
        assertEquals(SessionState.UNKNOWN, SessionScanner.sessionState(File("/nope/none.jsonl")))
    }

    @Test
    fun `fromOffset 이후 신규 엔트리가 없으면 UNKNOWN`() {
        val f = jsonl(assistant("end_turn"))
        assertEquals(SessionState.UNKNOWN, SessionScanner.sessionState(f, fromOffset = f.length()))
    }

    @Test
    fun `fromOffset 이전의 DONE 은 자기 것으로 오판하지 않는다`() {
        val f = jsonl(assistant("end_turn"))
        val offset = f.length()
        // 전송 이후 새 user 엔트리만 쌓인 상태 → 아직 WORKING
        f.appendText("""{"type":"user","message":{"content":"새 작업"}}""" + "\n")
        assertEquals(SessionState.WORKING, SessionScanner.sessionState(f, fromOffset = offset))
    }

    @Test
    fun `lastQuestionText 는 질문들을 이어붙인다`() {
        val content = """[{"type":"tool_use","name":"AskUserQuestion","input":{"questions":[{"question":"A?"},{"question":"B?"}]}}]"""
        assertEquals("A? / B?", SessionScanner.lastQuestionText(jsonl(assistant("tool_use", content))))
    }

    @Test
    fun `lastQuestionText 는 ExitPlanMode 에 고정 문구`() {
        val content = """[{"type":"tool_use","name":"ExitPlanMode","input":{}}]"""
        assertEquals("Review the plan", SessionScanner.lastQuestionText(jsonl(assistant("tool_use", content))))
    }

    @Test
    fun `lastAssistantText 는 text 블록만 모은다`() {
        val content = """[{"type":"text","text":"앞"},{"type":"tool_use","name":"Bash"},{"type":"text","text":"뒤"}]"""
        assertEquals("앞 뒤", SessionScanner.lastAssistantText(jsonl(assistant("end_turn", content))))
    }

    @Test
    fun `lastContextTokens 는 최신 assistant usage 를 쓴다`() {
        assertEquals(60, SessionScanner.lastContextTokens(jsonl(USAGE_60)))
    }

    @Test
    fun `lastContextTokens 는 compact 직후에 압축 전 usage 를 쓰지 않는다`() {
        val f = jsonl(
            USAGE_60,
            """{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"manual"}}"""
        )
        assertEquals(0, SessionScanner.lastContextTokens(f))
    }

    @Test
    fun `lastContextTokens 는 compact 이후의 새 usage 는 쓴다`() {
        val f = jsonl(
            """{"type":"assistant","message":{"stop_reason":"end_turn","usage":{"input_tokens":900000,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
            """{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto"}}""",
            USAGE_60
        )
        assertEquals(60, SessionScanner.lastContextTokens(f))
    }

    @Test
    fun `lastContextTokens 는 isCompactSummary 도 경계로 본다`() {
        val f = jsonl(USAGE_60, """{"type":"user","isCompactSummary":true,"message":{"content":"요약"}}""")
        assertEquals(0, SessionScanner.lastContextTokens(f))
    }

    /** 모델은 압축과 무관하다 — 토큰만 0 이 되고 모델명은 남아야 한다 */
    @Test
    fun `lastContext 는 compact 후에도 모델명은 유지한다`() {
        val snap = SessionScanner.lastContext(
            jsonl(
                assistant("end_turn"),
                """{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto"}}"""
            )
        )
        assertEquals(0, snap.tokens)
        assertEquals("claude-opus-5", snap.model)
    }

    /** claude 가 붙인 제목을 그대로 쓴다 — 첫 프롬프트를 자르는 것보다 정확하다 */
    @Test
    fun `lastContext 는 ai-title 을 제목으로 읽는다`() {
        val snap = SessionScanner.lastContext(
            jsonl(
                USAGE_60,
                """{"type":"ai-title","aiTitle":"알람 트리거 수 계산 로직 확인","sessionId":"x"}"""
            )
        )
        assertEquals("알람 트리거 수 계산 로직 확인", snap.title)
        assertEquals(60, snap.tokens)
    }

    /** 제목이 여러 번 갱신되면 마지막 것이 최신이다 */
    @Test
    fun `lastContext 는 마지막 ai-title 을 쓴다`() {
        val snap = SessionScanner.lastContext(
            jsonl(
                """{"type":"ai-title","aiTitle":"옛 제목","sessionId":"x"}""",
                USAGE_60,
                """{"type":"ai-title","aiTitle":"새 제목","sessionId":"x"}"""
            )
        )
        assertEquals("새 제목", snap.title)
    }

    @Test
    fun `lastContext 는 ai-title 이 없으면 빈 제목`() {
        assertEquals("", SessionScanner.lastContext(jsonl(USAGE_60)).title)
    }

    @Test
    fun `lastModel 은 최신 assistant 모델`() {
        assertEquals("claude-opus-5", SessionScanner.lastModel(jsonl(assistant("end_turn"))))
    }

    companion object {
        /** input 10 + 캐시 생성 20 + 캐시 읽기 30 = 60 (output 은 세지 않는다) */
        private const val USAGE_60 =
            """{"type":"assistant","message":{"stop_reason":"end_turn","usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":30,"output_tokens":999}}}"""
    }
}
