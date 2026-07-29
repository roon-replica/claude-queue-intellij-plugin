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
        return Registration { waiters.remove(sessionId) }
    }

    fun interface Registration {
        fun cancel()
    }

    data class StopSignal(val sessionId: String, val transcriptPath: String?, val cwd: String?)

    /** 훅이 파일을 남길 디렉토리. 플러그인이 만들고 비운다 */
    val stopsDir: File
        get() = File(System.getProperty("user.home"), ".task-queue/stops")

    /** 훅에 넣을 명령 — mktemp 로 파일명이 겹치지 않게 한다 */
    fun hookCommand(): String {
        stopsDir.mkdirs()
        val template = File(stopsDir, "stop-XXXXXXXX.json").absolutePath
        return "cat > \"\$(mktemp ${shellQuote(template)})\""
    }

    private fun ensurePolling() {
        if (poller != null) return
        poller = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::sweep, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }

    /** 새 훅 파일을 읽어 해당 세션 대기자에게 전달하고, 파일은 지운다 */
    private fun sweep() {
        val files = stopsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (file in files) {
            val signal = parse(file)
            file.delete()
            if (signal == null) continue

            val waiter = waiters.remove(signal.sessionId) ?: continue
            if (file.lastModified() + CLOCK_SLACK_MS < waiter.since) {
                // 전송 전에 발생한 신호 — 이전 턴의 잔여물이므로 버린다
                waiters[signal.sessionId] = waiter
                continue
            }
            runCatching { waiter.onStop(signal) }
                .onFailure { thisLogger().warn("Stop 처리 실패", it) }
        }
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
        )
    } catch (e: Exception) {
        thisLogger().warn("Stop 훅 파일 파싱 실패: ${file.name}", e)
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

        fun getInstance(): StopHookWatcher = service()
    }
}
