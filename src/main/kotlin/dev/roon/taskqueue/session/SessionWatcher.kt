package dev.roon.taskqueue.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 세션 파일 상태를 폴링으로 추적한다.
 * VFS 를 쓰지 않는 이유: 외부 프로세스(claude CLI)가 쓰는 파일이라 IDE 가 변경을 늦게 알거나 놓친다.
 */
@Service
class SessionWatcher : Disposable {

    private val executor = AppExecutorUtil.getAppScheduledExecutorService()

    /**
     * 상태가 바뀔 때마다 [onState] 를 호출한다. 종료 상태(DONE/IDLE)에 도달하면 감시를 멈춘다.
     * @param fromOffset 이 오프셋 이후 엔트리만 판정 대상 — 앞 작업의 완료를 오판하지 않게
     */
    fun watch(
        file: File,
        fromOffset: Long = 0,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        stopOnTerminal: Boolean = true,
        onState: (SessionState) -> Unit,
    ): Handle {
        val handle = Handle()
        var last: SessionState? = null

        handle.future = executor.scheduleWithFixedDelay({
            if (handle.stopped.get()) return@scheduleWithFixedDelay
            val state = SessionScanner.sessionState(file, fromOffset)
            if (state != last) {
                last = state
                onState(state)
            }
            if (stopOnTerminal && state.isTerminal) handle.cancel()
        }, 0, intervalMs, TimeUnit.MILLISECONDS)

        return handle
    }

    class Handle {
        internal val stopped = AtomicBoolean(false)
        internal var future: ScheduledFuture<*>? = null

        val isStopped: Boolean get() = stopped.get()

        fun cancel() {
            if (stopped.compareAndSet(false, true)) future?.cancel(false)
        }
    }

    override fun dispose() = Unit

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L

        fun getInstance(): SessionWatcher = service()
    }
}
