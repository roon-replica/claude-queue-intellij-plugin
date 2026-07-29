package dev.roon.taskqueue.ui

import com.intellij.ui.JBColor
import dev.roon.taskqueue.queue.TaskStatus
import java.awt.Color

/**
 * 상태 색. 컬럼 헤더와 카드 상태점이 **같은 값**을 써야 둘의 연결이 읽힌다.
 * 라이트/다크 양쪽 값을 지정해 테마에 따라 자동 선택된다.
 */
object StatusColors {

    val TODO: JBColor = JBColor(0x6E7781, 0x8B949E)

    /** 신호등 읽기 — 주황은 '대기', 초록은 '진행'. 파랑은 대기라는 뜻이 읽히지 않는다 */
    val QUEUED: JBColor = JBColor(0xC17E00, 0xE3A93A)
    val RUNNING: JBColor = JBColor(0x1F8B4C, 0x4CB782)
    val DONE: JBColor = JBColor(0x6E7781, 0x8B949E)
    val FAILED: JBColor = JBColor(0xD1383D, 0xE5534B)

    fun of(status: TaskStatus): JBColor = when (status) {
        TaskStatus.TODO -> TODO
        TaskStatus.QUEUED -> QUEUED
        TaskStatus.RUNNING -> RUNNING
        TaskStatus.DONE -> DONE
        TaskStatus.FAILED -> FAILED
        TaskStatus.CANCELED -> TODO
    }

    /** 두 색을 [ratio] 만큼 섞는다 — 알파 대신 직접 섞어야 리스트 배경과 자연스럽다 */
    fun blend(base: Color, tint: Color, ratio: Float): Color {
        if (ratio <= 0f) return base
        val r = ratio.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * r).toInt().coerceIn(0, 255)
        return Color(
            mix(base.red, tint.red),
            mix(base.green, tint.green),
            mix(base.blue, tint.blue),
        )
    }
}
