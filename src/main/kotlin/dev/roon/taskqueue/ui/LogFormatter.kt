package dev.roon.taskqueue.ui

import dev.roon.taskqueue.cli.StreamEvent

/**
 * stream-json 한 줄을 사람이 읽는 한 줄로. 잡음(hook 등)은 null 로 버린다.
 */
object LogFormatter {

    fun format(line: String): String? {
        val e = StreamEvent.parse(line) ?: return null

        // 훅 시작/응답은 노이즈
        if (e.type == "system" && e.subtype?.startsWith("hook") == true) return null

        if (e.type == "system" && e.subtype == "init") {
            val model = e.raw.get("model")?.takeIf { !it.isJsonNull }?.asString ?: "?"
            return "▸ session started  ($model)"
        }

        if (e.type == "rate_limit_event") {
            return "· rate limit: ${e.rateLimitStatus}"
        }

        if (e.isResult) {
            val secs = e.durationMs?.let { "%.1fs".format(it / 1000.0) } ?: "-"
            val cost = e.totalCostUsd?.let { "$%.3f".format(it) } ?: "-"
            return if (e.isError) "✘ failed  $secs  $cost" else "✔ done  $secs  $cost"
        }

        if (e.type == "assistant") {
            val text = e.assistantText?.trim()
            if (!text.isNullOrEmpty()) return text
            // 텍스트 없는 assistant = 도구 호출
            val tools = toolNames(e)
            if (tools.isNotEmpty()) return "· ${tools.joinToString(", ")}"
            return null
        }

        if (e.type == "user") {
            // tool_result 는 양이 많아 요약만
            return "· tool result"
        }

        return null
    }

    /** assistant content 의 tool_use 이름 + 눈에 띄는 인자 */
    private fun toolNames(e: StreamEvent): List<String> {
        val content = e.raw.getAsJsonObject("message")?.getAsJsonArray("content") ?: return emptyList()
        return content.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (o.get("type")?.asString != "tool_use") return@mapNotNull null
            val name = o.get("name")?.asString ?: return@mapNotNull null
            val hint = hintOf(o)
            if (hint != null) "$name($hint)" else name
        }
    }

    /** 도구 인자 중 사람이 알아볼 값 하나 */
    private fun hintOf(toolUse: com.google.gson.JsonObject): String? {
        val input = toolUse.get("input")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        for (key in listOf("file_path", "path", "pattern", "command", "url", "prompt")) {
            val v = input.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString ?: continue
            return v.replace(Regex("\\s+"), " ").take(HINT_MAX)
        }
        return null
    }

    private const val HINT_MAX = 60
}
