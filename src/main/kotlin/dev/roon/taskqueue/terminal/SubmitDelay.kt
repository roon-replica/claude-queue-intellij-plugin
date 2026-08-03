package dev.roon.taskqueue.terminal

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * 프롬프트 본문과 실행(Enter)을 **따로 보내기 위한 간격.**
 *
 * 한 덩어리로 보내면 큰 입력에서 실행이 되지 않는다(실측). 터미널·TUI 가 그것을 타이핑이
 * 아니라 붙여넣기로 취급하면 끝의 `\r` 이 실행이 아니라 입력창의 줄바꿈 한 칸이 되기
 * 때문이다. 그래서 본문이 다 들어간 뒤에 Enter 를 **별개 입력으로** 보낸다.
 *
 * 간격은 크기에 따라 늘린다 — 큰 본문은 터미널이 삼키는 데 시간이 더 걸린다.
 */
object SubmitDelay {

    private const val BASE_MS = 150L
    private const val PER_BYTE_MS = 0.05
    private const val MAX_MS = 1_500L
    private const val ENTER = "\r"

    /** 본문 크기에 맞는 대기 시간 */
    fun forText(text: String): Long =
        (BASE_MS + text.toByteArray().size * PER_BYTE_MS).toLong().coerceAtMost(MAX_MS)

    /** [text] 를 보낸 뒤 그 크기에 맞게 기다렸다 Enter 를 보낸다 */
    fun submitAfter(text: String, sendEnter: (String) -> Unit) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { runCatching { sendEnter(ENTER) } },
            forText(text), TimeUnit.MILLISECONDS,
        )
    }
}
