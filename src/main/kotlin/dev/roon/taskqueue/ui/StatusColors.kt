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

    /** 주황은 '대기' — 파랑은 대기라는 뜻이 읽히지 않아 쓰지 않는다 */
    val QUEUED: JBColor = JBColor(0xC17E00, 0xE3A93A)

    /**
     * '진행' 은 파랑. 초록은 '완료·성공' 으로 읽히기 쉬워 도는 중인 것과 헷갈렸다 —
     * 파랑은 활동 중을 뜻하는 관례가 있고, 실패 빨강·대기 주황과도 확실히 갈린다.
     * 구간 헤더·맥동 테두리·카드 상태점이 모두 이 값을 쓴다.
     */
    val RUNNING: JBColor = JBColor(0x1F6FEB, 0x58A6FF)
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
