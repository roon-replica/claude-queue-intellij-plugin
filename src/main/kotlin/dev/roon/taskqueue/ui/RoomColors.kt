package dev.roon.taskqueue.ui

import com.intellij.ui.JBColor

/**
 * 방(터미널 탭) 컬럼 헤더 색.
 *
 * **탭 이름으로 색을 정한다** — 순서대로 돌리면 탭 하나가 닫힐 때 뒤의 색이 전부 밀려
 * 화면이 흔들린다. 이름 기준이면 그 탭은 언제나 같은 색이고, IDE 를 껐다 켜도 같다.
 *
 * 카드 상태점의 색(진행 초록·대기 주황·실패 빨강)과 겹치지 않게 고른다 —
 * 헤더 색이 상태로 읽히면 안 된다. 그래서 빨강·초록·노랑 자리를 자홍·청록·호박으로 비껴 썼다.
 */
object RoomColors {

    /** 라이트/다크 쌍. 서로 충분히 구분되는 색조로만 고른다 */
    private val PALETTE: List<JBColor> = listOf(
        JBColor(0x2E7CD6, 0x549BF0), // 파랑
        JBColor(0x8250DF, 0xA371F7), // 보라
        JBColor(0x0E8A8A, 0x36B5B5), // 청록
        JBColor(0xC2389A, 0xE06BBE), // 자홍
        JBColor(0xC2570B, 0xE8873F), // 호박
        JBColor(0x4B49C6, 0x7C7AE0), // 남색
    )

    /** 탭 이름이 같으면 언제나 같은 색 */
    fun of(room: String): JBColor = PALETTE[Math.floorMod(room.hashCode(), PALETTE.size)]
}
