package dev.roon.taskqueue.hook

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Claude Code 의 `Stop` 훅이 남긴 파일을 감시해 "턴 종료" 를 알린다.
 *
 * 대화형 세션은 열려 있는 동안 전사(jsonl)를 쓰지 않아 파일 기반 판정이 불가하다(실측).
 * 그래서 실행 시 `--settings` 로 Stop 훅을 심고, 그 훅이 남긴 JSON 을 완료 신호로 쓴다.
 *
 * 훅 payload: `{session_id, transcript_path, cwd, hook_event_name, stop_hook_active}`
 */
@Service
class StopHookWatcher : Disposable {

    /** sessionId → 대기 중인 구독 */
    private val waiters = ConcurrentHashMap<String, Waiter>()
    private var poller: ScheduledFuture<*>? = null

    /**
     * 세션의 다음 Stop 을 한 번 기다린다. **첫 신호만** 전달하고 구독을 끝낸다 —
     * 사용자가 그 터미널에서 이어서 대화하면 Stop 이 또 오는데, 그건 이 작업의 완료가 아니다.
     *
     * @param since 이 시각 이후에 도착한 신호만 인정
     */
    fun awaitStop(sessionId: String, since: Long, onStop: (StopSignal) -> Unit): Registration {
        waiters[sessionId] = Waiter(since, onStop)
        ensurePolling()
        return Registration {
            waiters.remove(sessionId)
            stopPollingIfIdle()
        }
    }

    fun interface Registration {
        fun cancel()
    }

    data class StopSignal(
    val sessionId: String,
    val transcriptPath: String?,
    val cwd: String?,
    /** 훅이 함께 넘겨주는 claude 의 마지막 답변 */
    val lastMessage: String?,
)

    /**
     * 훅이 파일을 남길 디렉토리.
     * **홈 아래는 쓸 수 없다** — claude 훅은 샌드박스에서 돌아 홈 경로 쓰기가 막힌다(실측).
     * temp 디렉토리는 허용되므로 그쪽을 쓴다.
     */
    val stopsDir: File
        get() = File(System.getProperty("java.io.tmpdir"), "task-queue/stops")

    /**
     * 훅에 넣을 명령. **세션 ID 를 파일명에 써서 고정 경로로 만든다.**
     * `$(mktemp …)` 같은 명령 치환은 쓰지 않는다 — 세션 ID 가 유일하므로 고정 경로로 충분하다.
     */
    fun hookCommand(sessionId: String): String {
        stopsDir.mkdirs()
        val target = File(stopsDir, "stop${sessionId.replace("-", "")}.json").absolutePath
        return "cat > ${shellQuote(target)}"
    }

    /** 대기자가 있을 때만 폴링한다 — 유휴 상태에서 헛도는 비용을 없앤다 */
    private fun ensurePolling() {
        synchronized(this) {
            if (poller != null) return
            poller = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(::sweep, 0, POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopPollingIfIdle() {
        synchronized(this) {
            if (waiters.isNotEmpty()) return
            poller?.cancel(false)
            poller = null
        }
    }

    /** 새 훅 파일을 읽어 해당 세션 대기자에게 전달하고, 파일은 지운다 */
    private fun sweep() {
        val files = stopsDir.listFiles { f -> f.isFile && f.name.startsWith("stop") && f.name.endsWith(".json") } ?: return
        for (file in files) {
            val signal = parse(file)
            if (signal == null) {
                pruneIfStale(file)
                continue
            }

            val waiter = waiters[signal.sessionId]
            if (waiter == null) {
                // 우리 대기 대상이 아니다 — 남의 파일일 수 있으니 지우지 않고 오래된 것만 정리
                pruneIfStale(file)
                continue
            }
            if (file.lastModified() + CLOCK_SLACK_MS < waiter.since) {
                continue // 전송 전 신호 — 이전 턴 잔여물
            }
            waiters.remove(signal.sessionId)
            file.delete()
            runCatching { waiter.onStop(signal) }
                .onFailure { thisLogger().warn("Stop handler failed", it) }
        }
        stopPollingIfIdle()
    }

    /** 오래 방치된 파일만 정리한다 */
    private fun pruneIfStale(file: File) {
        if (System.currentTimeMillis() - file.lastModified() > STALE_MS) file.delete()
    }

    private fun parse(file: File): StopSignal? = try {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        // 훅이 유발한 재진입은 완료가 아니다
        if (root.get("stop_hook_active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return null
        val sessionId = root.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        StopSignal(
            sessionId = sessionId,
            transcriptPath = root.get("transcript_path")?.takeIf { it.isJsonPrimitive }?.asString,
            cwd = root.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString,
            lastMessage = root.get("last_assistant_message")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    } catch (e: Exception) {
        thisLogger().warn("Failed to parse Stop hook file: ${file.name}", e)
        null
    }

    private fun shellQuote(path: String): String =
        if (path.none { it.isWhitespace() || it in "'\"$`\\" }) path
        else "'" + path.replace("'", "'\\''") + "'"

    override fun dispose() {
        poller?.cancel(false)
        poller = null
        waiters.clear()
    }

    private class Waiter(val since: Long, val onStop: (StopSignal) -> Unit)

    companion object {
        private const val POLL_MS = 500L

        /** 파일 mtime 과 전송 시각의 미세한 역전을 허용 */
        private const val CLOCK_SLACK_MS = 2_000L

        /** 주인 없는 훅 파일을 정리하는 기준 */
        private const val STALE_MS = 5 * 60_000L

        fun getInstance(): StopHookWatcher = service()
    }
}
