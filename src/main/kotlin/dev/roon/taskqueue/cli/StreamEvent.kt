package dev.roon.taskqueue.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * claude -p --output-format stream-json 이 내보내는 JSON 한 줄.
 * 필요한 필드만 얕게 읽는다 — 전체 스키마를 모델링하지 않는다.
 */
class StreamEvent(val raw: JsonObject) {

    val type: String? get() = raw.str("type")
    val subtype: String? get() = raw.str("subtype")
    val sessionId: String? get() = raw.str("session_id")

    /** init 이벤트의 cwd */
    val cwd: String? get() = raw.str("cwd")

    /** assistant 이벤트의 텍스트 블록을 이어붙인 값 */
    val assistantText: String?
        get() {
            if (type != "assistant") return null
            val content = raw.getAsJsonObject("message")?.getAsJsonArray("content") ?: return null
            return content.mapNotNull { it.asJsonObject?.str("text") }
                .joinToString("")
                .ifEmpty { null }
        }

    /** 최종 result 이벤트 여부 */
    val isResult: Boolean get() = type == "result"
    val isError: Boolean get() = raw.get("is_error")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
    val resultText: String? get() = raw.str("result")
    val totalCostUsd: Double? get() = raw.get("total_cost_usd")?.takeIf { !it.isJsonNull }?.asDouble
    val durationMs: Long? get() = raw.get("duration_ms")?.takeIf { !it.isJsonNull }?.asLong

    /** rate_limit_event 의 상태 (allowed / … ) */
    val rateLimitStatus: String?
        get() = raw.getAsJsonObject("rate_limit_info")?.str("status")

    val outputTokens: Long?
        get() = raw.getAsJsonObject("usage")?.get("output_tokens")
            ?.takeIf { !it.isJsonNull }?.asLong

    companion object {
        /** 파싱 실패(부분 라인 등)는 null 로 흘린다. 스트림을 중단시키지 않는다. */
        fun parse(line: String): StreamEvent? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                StreamEvent(JsonParser.parseString(trimmed).asJsonObject)
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
