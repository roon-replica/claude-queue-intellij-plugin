package dev.roon.taskqueue.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

/**
 * 우리가 보낸 프롬프트로 **그 프롬프트를 받은 세션**을 찾아낸다.
 *
 * 사용자가 직접 연 터미널 탭은 세션 ID 를 알 수 없다. mtime 이 최신인 파일을 찍으면
 * 같은 repo 에서 claude 를 여러 개 돌릴 때 오판하므로, 프롬프트 본문으로 특정한다.
 * 밀어넣은 문장은 그 세션 jsonl 에 user 항목으로 그대로 남는다.
 */
object SessionFinder {

    /** 끝부분만 본다 — 프롬프트는 방금 들어갔으므로 파일 앞쪽을 읽을 이유가 없다 */
    private const val TAIL_BYTES = 256L * 1024

    /** 파일 mtime 과 전송 시각의 미세한 역전을 허용 */
    private const val CLOCK_SLACK_MS = 2_000L

    private val WHITESPACE = Regex("\\s+")

    /**
     * @param since 이 시각 이후 갱신된 파일만 후보로 본다 (같은 문장을 재전송했을 때
     *              이전 턴의 파일을 집지 않게 한다)
     * @return 찾은 세션 파일, 아직 안 나타났으면 null
     */
    fun findByPrompt(cwd: String, prompt: String, since: Long): File? {
        val needle = normalize(prompt)
        if (needle.isEmpty()) return null

        return SessionPaths.listSessionFiles(cwd)
            .filter { it.lastModified() >= since - CLOCK_SLACK_MS }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { containsUserPrompt(it, needle) }
    }

    /** 프롬프트가 잘려 기록될 수 있어 양방향 '포함' 으로 본다 */
    internal fun containsUserPrompt(file: File, needle: String): Boolean =
        tailLines(file).any { line -> matches(line, needle) }

    internal fun matches(line: String, needle: String): Boolean {
        val obj = runCatching { JsonParser.parseString(line.trim()).asJsonObject }.getOrNull() ?: return false
        if (obj.str("type") != "user") return false
        val text = normalize(userText(obj))
        return text.isNotEmpty() && (text.contains(needle) || needle.contains(text))
    }

    private fun userText(entry: JsonObject): String {
        val content = entry.get("message")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        if (!content.isJsonArray) return ""
        return content.asJsonArray.joinToString("") { part ->
            part.takeIf { it.isJsonObject }?.asJsonObject?.str("text") ?: ""
        }
    }

    internal fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun tailLines(file: File): List<String> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            val size = file.length()
            val start = maxOf(0L, size - TAIL_BYTES)
            val buf = ByteArray((size - start).toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(buf)
            }
            String(buf, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
}
