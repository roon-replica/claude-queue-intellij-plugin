package dev.roon.taskqueue.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * 탭의 tty 가 붙을 때까지 기다린다.
 *
 * **방금 연 탭은 tty 가 아직 없다.** 그 상태에서 명령을 넣으면 그대로 유실되고,
 * `hasRunningCommand()` 도 셸이 rc 파일을 읽는 중이면 엉뚱하게 답한다.
 * 명령을 보내는 쪽은 어디서든 이걸 거쳐야 한다 — 안 그러면 "될 때도 있고 안 될 때도 있는"
 * 증상이 난다(실측).
 */
object TabReadiness {

    private const val POLL_MS = 150L
    private const val TIMEOUT_MS = 15_000L

    /**
     * 가라앉히는 시간. 두 가지를 같이 기다린다 —
     * 셸이 rc 파일을 읽는 동안은 실행중 판별이 흔들리고,
     * 탭을 앞으로 가져오는 것도 비동기라 그 전에는 터미널 크기가 확정되지 않는다.
     */
    private const val SETTLE_MS = 250L

    private fun settleThen(action: () -> Unit) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ApplicationManager.getApplication().invokeLater(action) },
            SETTLE_MS, TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 준비되면 [onReady] 를, 시간 안에 안 되면 [onTimeout] 을 **EDT 에서** 부른다.
     *
     * 먼저 탭을 화면에 띄운다 — 터미널은 탭 UI 가 보일 때까지 세션 시작을 미루므로
     * (`deferSessionStartUntilUiShown`), 가려진 탭은 기다려도 영원히 붙지 않는다.
     */
    fun await(
        project: Project,
        tab: TerminalSessionRegistry.Tab,
        onTimeout: () -> Unit,
        onReady: () -> Unit,
    ) {
        if (tab.ready) {
            // 이미 준비됐어도 곧바로 보내지 않는다 — 탭을 앞으로 가져오는 것이 비동기라
            // 레이아웃이 잡히기 전에 시작하면 TUI 가 잘못된 크기로 그려진다
            settleThen(onReady)
            return
        }
        TerminalTabFocuser.focus(project, tab.label, moveFocus = false)

        val startedAt = System.currentTimeMillis()
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        lateinit var poll: java.util.concurrent.ScheduledFuture<*>
        poll = scheduler.scheduleWithFixedDelay({
            when {
                tab.ready -> {
                    poll.cancel(false)
                    settleThen(onReady)
                }

                System.currentTimeMillis() - startedAt > TIMEOUT_MS -> {
                    poll.cancel(false)
                    ApplicationManager.getApplication().invokeLater(onTimeout)
                }
            }
        }, 0, POLL_MS, TimeUnit.MILLISECONDS)
    }
}
