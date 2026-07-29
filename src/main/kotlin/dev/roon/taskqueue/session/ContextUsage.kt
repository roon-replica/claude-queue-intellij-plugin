package dev.roon.taskqueue.session

import com.google.gson.JsonObject

/**
 * jsonl assistant 레코드의 message.usage 를 컨텍스트 점유량으로 환산.
 * claude-talk `context-usage.ts` 이식.
 */
object ContextUsage {

    private const val M = 1_000_000L
    private const val K200 = 200_000L

    /** 자동 compact 임박 구간을 heavy 로 본다 */
    const val WARN_PCT = 60
    const val HEAVY_PCT = 80

    /** 1M 컨텍스트 모델 패턴. 미상 모델은 보수적으로 200K */
    private val LIMIT_1M = listOf(
        Regex("opus-(5|4-[678])"),
        Regex("sonnet-(5|4-6)"),
        Regex("fable-5"),
        Regex("mythos"),
    )

    /**
     * 컨텍스트 점유 = input + 캐시 생성 + 캐시 읽기.
     * output_tokens 는 다음 턴 input 에 이미 포함되므로 제외(이중 계산 방지).
     */
    fun usageTokens(usage: JsonObject?): Long {
        if (usage == null) return 0
        val n = usage.num("input_tokens") +
            usage.num("cache_creation_input_tokens") +
            usage.num("cache_read_input_tokens")
        return if (n > 0) n else 0
    }

    /** 미상 모델이 한도를 넘겼으면 실제 값에 맞춰 올려 잡는다 (100%+ 표시 방지) */
    fun contextLimit(model: String, tokens: Long = 0): Long {
        val m = model.lowercase()
        var limit = if (LIMIT_1M.any { it.containsMatchIn(m) }) M else K200
        if (tokens > limit) limit = if (tokens > M) tokens else M
        return limit
    }

    /** /context 표시와 같은 형식: 900 / 130.3k / 200k / 1m / 1.2m */
    fun formatTokens(n: Long): String {
        if (n < 1000) return "$n"
        val (v, unit) = if (n < M) (n / 1000.0) to "k" else (n / M.toDouble()) to "m"
        val s = "%.1f".format(v)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
    }

    fun percent(tokens: Long, limit: Long): Int {
        if (tokens <= 0 || limit <= 0) return 0
        return minOf(100, Math.round(tokens.toDouble() / limit * 100).toInt())
    }

    fun level(pct: Int): Level = when {
        pct >= HEAVY_PCT -> Level.HEAVY
        pct >= WARN_PCT -> Level.WARN
        else -> Level.OK
    }

    /** '130.3k/1m (13%)' — 토큰 모르면 빈 문자열 */
    fun label(tokens: Long, model: String): String {
        if (tokens <= 0) return ""
        val limit = contextLimit(model, tokens)
        return "${formatTokens(tokens)}/${formatTokens(limit)} (${percent(tokens, limit)}%)"
    }

    enum class Level { OK, WARN, HEAVY }

    private fun JsonObject.num(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong ?: 0
}
