package dev.roon.taskqueue.nav

import java.io.File

/**
 * 작업 결과 텍스트에서 `경로:라인` 참조를 뽑아낸다.
 * 실제 존재하는 파일만 남긴다 — 그래야 잡음(버전 문자열, 패키지명)이 걸러진다.
 */
object FileRefs {

    data class Ref(val path: String, val line: Int?) {
        fun label(): String = if (line != null) "$path:$line" else path
    }

    /** 확장자를 가진 경로(절대/상대) + 선택적 :line */
    private val PATTERN = Regex("""(/?[A-Za-z0-9_.@\-]+(?:/[A-Za-z0-9_.@\-]+)*\.[A-Za-z0-9]{1,12})(?::(\d+))?""")

    fun extract(text: String, baseDir: File, limit: Int = 50): List<Ref> {
        val seen = LinkedHashMap<String, Ref>()
        for (m in PATTERN.findAll(text)) {
            val raw = m.groupValues[1]
            val line = m.groupValues[2].toIntOrNull()
            val path = normalize(raw, baseDir) ?: continue
            val key = "$path:${line ?: 0}"
            if (!seen.containsKey(key)) seen[key] = Ref(path, line)
            if (seen.size >= limit) break
        }
        return seen.values.toList()
    }

    /** baseDir 기준 상대경로로 정규화. 존재하지 않으면 null */
    private fun normalize(raw: String, baseDir: File): String? {
        val candidates = if (raw.startsWith("/")) listOf(File(raw)) else listOf(File(baseDir, raw), File(raw))
        val hit = candidates.firstOrNull { it.isFile } ?: return null
        val base = baseDir.absolutePath.trimEnd('/')
        val abs = hit.absolutePath
        return if (abs.startsWith("$base/")) abs.removePrefix("$base/") else abs
    }

    /** 참조를 절대 경로로 되돌린다 */
    fun resolve(ref: Ref, baseDir: File): File =
        if (ref.path.startsWith("/")) File(ref.path) else File(baseDir, ref.path)
}
