package dev.roon.taskqueue.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

/**
 * 세션 jsonl 끝부분을 스캔해 상태를 판정한다.
 * claude-talk `session-scanner.ts` 의 검증된 판정 규칙 이식.
 */
object SessionScanner {

    /** 뒤에서 이만큼만 읽는다 — 전체 파일을 읽지 않는다 */
    const val TAIL_BYTES = 128 * 1024L

    /** 응답 전까지 tool_result 없이 마지막 라인으로 남는 툴 */
    private val WAIT_TOOLS = setOf("AskUserQuestion", "ExitPlanMode")

    private const val INTERRUPT_MARK = "[Request interrupted by user"

    /**
     * @param fromOffset 이 바이트 이후에 쌓인 엔트리만 본다.
     *   자동작업 러너가 앞 작업의 DONE 을 자기 것으로 오판하지 않게 하는 장치.
     */
    fun sessionState(file: File, fromOffset: Long = 0): SessionState {
        val lines = readTailLines(file, fromOffset) ?: return SessionState.UNKNOWN

        for (line in lines.asReversed()) {
            val d = parseObject(line) ?: continue
            if (isLocalNoise(d)) continue

            val type = d.str("type")

            // compact 경계까지 왔다 = 압축 후 아무 일도 없음
            // 자동 compact 는 이어서 도는 중, 수동은 턴 종료
            if (type == "system" && d.str("subtype") == "compact_boundary") {
                val trigger = d.obj("compactMetadata")?.str("trigger")
                return if (trigger == "auto") SessionState.WORKING else SessionState.DONE
            }

            if (type == "assistant") {
                val message = d.obj("message")
                val stopReason = message?.str("stop_reason")
                return when {
                    // 마지막 assistant 가 질문 툴이면 사용자 응답 대기
                    stopReason == "tool_use" ->
                        if (hasWaitTool(message.arr("content"))) SessionState.WAITING else SessionState.WORKING
                    // stop_reason null = 스트리밍 중
                    stopReason == null -> SessionState.WORKING
                    else -> SessionState.DONE
                }
            }

            if (type == "user") {
                // 인터럽트로 멈춤 → IDLE, 그 외(tool_result/새 프롬프트)는 진행 중
                return if (isInterrupted(d.obj("message")?.get("content"))) SessionState.IDLE
                else SessionState.WORKING
            }

            // ai-title / last-prompt / summary / 기타 system 메타는 건너뛴다
        }
        return SessionState.UNKNOWN
    }

    /** 마지막 질문 툴의 질문 텍스트 (WAITING 알림 body 용) */
    fun lastQuestionText(file: File): String {
        val lines = readTailLines(file, 0) ?: return ""

        for (line in lines.asReversed()) {
            val d = parseObject(line) ?: continue
            if (d.str("type") != "assistant") continue

            val content = d.obj("message")?.arr("content") ?: return ""
            val toolUse = content.mapNotNull { it.asJsonObjectOrNull() }
                .firstOrNull { it.str("type") == "tool_use" && it.str("name") in WAIT_TOOLS }
                ?: return ""

            if (toolUse.str("name") == "ExitPlanMode") return "Review the plan"

            val questions = toolUse.obj("input")?.arr("questions") ?: return ""
            return questions.mapNotNull { it.asJsonObjectOrNull()?.str("question")?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" / ")
                .take(140)
        }
        return ""
    }

    /** 마지막 assistant 메시지의 텍스트 앞 140자 (완료 알림 body 용) */
    fun lastAssistantText(file: File): String {
        val lines = readTailLines(file, 0) ?: return ""

        for (line in lines.asReversed()) {
            val d = parseObject(line) ?: continue
            if (d.str("type") != "assistant") continue

            val content = d.obj("message")?.arr("content") ?: return ""
            return content.mapNotNull { it.asJsonObjectOrNull() }
                .filter { it.str("type") == "text" }
                .mapNotNull { it.str("text") }
                .joinToString(" ")
                .replace(WHITESPACE, " ")
                .trim()
                .take(140)
        }
        return ""
    }

    /** 점유율·목록 표시에 필요한 값 묶음 */
    data class ContextSnapshot(val tokens: Long, val model: String, val title: String = "") {
        companion object {
            val NONE = ContextSnapshot(0, "", "")
        }
    }

    /**
     * 컨텍스트 점유 토큰 + 모델명을 **한 번의 읽기·파싱으로** 가져온다.
     * 2초마다 폴링되는 경로라 파일을 두 번 훑지 않는다.
     *
     * **compact 이전 토큰 값은 버린다** — 압축이 일어나면 실제 점유는 확 줄지만 직전
     * assistant 레코드에는 압축 전 usage 가 그대로 남아 있다. 그걸 읽으면 실제보다
     * 훨씬 높은 값을 보여주므로, compact 경계보다 뒤에 있는 usage 만 유효로 본다.
     * 압축 직후(새 응답 전)에는 0 = "모름" 이라 표시가 아예 나가지 않는다.
     * 경계가 tail 창 밖으로 밀려났다면 창 안은 전부 압축 이후라 그대로 유효하다.
     *
     * 모델명은 압축과 무관하므로 경계를 넘어서도 계속 찾는다.
     */
    fun lastContext(file: File): ContextSnapshot {
        val lines = readTailLines(file, 0) ?: return ContextSnapshot.NONE
        val objs = lines.map { parseObject(it) }

        val lastCompact = objs.indexOfLast { it != null && isCompactBoundary(it) }
        var tokens = 0L
        var tokensDone = false
        var model = ""
        var title = ""

        for (i in objs.indices.reversed()) {
            val d = objs[i] ?: continue

            // claude 가 직접 붙인 대화 제목 — 첫 프롬프트를 자르는 것보다 훨씬 낫다
            if (title.isEmpty() && d.str("type") == "ai-title") {
                d.str("aiTitle")?.let { title = it }
                continue
            }
            if (d.str("type") != "assistant") continue
            val message = d.obj("message")

            if (model.isEmpty()) message?.str("model")?.let { model = it }
            if (!tokensDone) {
                if (i < lastCompact) {
                    tokensDone = true // 압축 이전 값 — 쓰지 않는다
                } else {
                    message?.obj("usage")?.let {
                        tokens = ContextUsage.usageTokens(it)
                        tokensDone = true
                    }
                }
            }
            if (tokensDone && model.isNotEmpty() && title.isNotEmpty()) break
        }
        return ContextSnapshot(tokens, model, title)
    }

    /** compact 경계 레코드 — 시스템 경계 표식 또는 그 결과로 삽입된 요약 */
    private fun isCompactBoundary(d: JsonObject): Boolean =
        (d.str("type") == "system" && d.str("subtype") == "compact_boundary") || d.bool("isCompactSummary")

    /** 최신 assistant 의 message.usage → 컨텍스트 점유 토큰. 못 찾으면 0 */
    fun lastContextTokens(file: File): Long = lastContext(file).tokens

    /** 최신 assistant 의 모델명. 못 찾으면 "" */
    fun lastModel(file: File): String = lastContext(file).model

    // --- 내부 ---

    /** null = 읽을 신규 바이트가 없음 (파일 없음/빈 파일/fromOffset 이후 없음) */
    private fun readTailLines(file: File, fromOffset: Long): List<String>? {
        return try {
            if (!file.isFile) return null
            val size = file.length()
            val start = maxOf(0L, fromOffset, size - TAIL_BYTES)
            val len = size - start
            if (len <= 0) return null

            val buf = ByteArray(len.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(buf)
            }
            String(buf, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseObject(line: String): JsonObject? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        return try {
            JsonParser.parseString(t).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 모델을 돌리지 않는 잡음 — /compact 요약, 로컬 슬래시 명령 에코/출력.
     * 이걸 WORKING 으로 세면 compact 후 세션이 계속 진행중으로 남는다.
     */
    private fun isLocalNoise(d: JsonObject): Boolean {
        if (d.bool("isCompactSummary")) return true
        if (d.str("type") != "user") return false
        if (d.bool("isMeta")) return true
        val s = userText(d.obj("message")?.get("content")).trimStart()
        return s.startsWith("<local-command-stdout>") || s.startsWith("<command-name>")
    }

    private fun hasWaitTool(content: JsonArray?): Boolean =
        content?.mapNotNull { it.asJsonObjectOrNull() }
            ?.any { it.str("type") == "tool_use" && it.str("name") in WAIT_TOOLS } == true

    private fun isInterrupted(content: com.google.gson.JsonElement?): Boolean {
        if (content == null || content.isJsonNull) return false
        if (content.isJsonPrimitive) return content.asString.contains(INTERRUPT_MARK)
        if (!content.isJsonArray) return false
        return content.asJsonArray.mapNotNull { it.asJsonObjectOrNull() }.any { part ->
            val text = part.str("text") ?: part.str("content")
            text?.contains(INTERRUPT_MARK) == true
        }
    }

    private fun userText(content: com.google.gson.JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (!content.isJsonArray) return ""
        return content.asJsonArray.joinToString("") { it.asJsonObjectOrNull()?.str("text") ?: "" }
    }

    private val WHITESPACE = Regex("\\s+")
}

// --- gson 얕은 접근 헬퍼 ---

private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean == true

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
