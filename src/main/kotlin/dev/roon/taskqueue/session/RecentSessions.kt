package dev.roon.taskqueue.session

import java.io.File

/**
 * 이 프로젝트에서 최근에 쓴 claude 대화방 목록.
 *
 * 전사가 cwd 별 폴더로 나뉘어 있어 프로젝트 단위로 자연히 갈린다 —
 * 다른 프로젝트의 대화방이 섞여 보일 일이 없다.
 */
object RecentSessions {

    /** 목록에 띄울 개수. 그보다 오래된 대화를 다시 여는 일은 드물다 */
    const val LIMIT = 10

    data class Entry(
        val sessionId: String,
        val title: String,
        /** 마지막으로 쓴 시각 (전사 파일 mtime) */
        val lastUsedAt: Long,
        val tokens: Long,
        val model: String,
    ) {
        /** 컨텍스트 점유율. 토큰을 모르면 null — 0% 로 오해되지 않게 한다 */
        val percent: Int? get() = if (tokens > 0) ContextUsage.percent(tokens, limit) else null

        val limit: Long get() = ContextUsage.contextLimit(model, tokens)

        /** '130.3k/1m (13%)' — 토큰을 모르면 빈 문자열 */
        fun usageLabel(): String = ContextUsage.label(tokens, model)

        /** 제목이 없는 세션도 목록에서 고를 수 있어야 한다 */
        fun displayTitle(): String = title.ifEmpty { "(제목 없음) ${sessionId.take(8)}" }
    }

    /**
     * 최근 대화방 [limit] 개. **호출부는 배경 스레드여야 한다** — 전사 파일을 읽는다.
     *
     * mtime 으로 먼저 추리므로 읽는 파일은 [limit] 개를 넘지 않는다.
     */
    fun list(cwd: String, limit: Int = LIMIT): List<Entry> =
        SessionPaths.recentSessionFiles(cwd, limit).mapNotNull { entryOf(it) }

    private fun entryOf(file: File): Entry? = runCatching {
        val snapshot = SessionScanner.lastContext(file)
        Entry(
            sessionId = SessionPaths.sessionIdOf(file),
            title = snapshot.title,
            lastUsedAt = file.lastModified(),
            tokens = snapshot.tokens,
            model = snapshot.model,
        )
    }.getOrNull()
}
