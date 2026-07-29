package dev.roon.taskqueue.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskStatus
import dev.roon.taskqueue.session.SessionState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** 카드 = 상태점 + 프롬프트 1줄 + 메타 1줄 */
class TaskCardRenderer : JPanel(BorderLayout(JBUI.scale(6), 0)), ListCellRenderer<TaskEntry> {

    private val dot = StatusDot()
    private val title = JLabel()
    private val meta = JLabel().apply {
        font = JBFont.small()
    }

    init {
        border = JBUI.Borders.empty(4, 6)
        val texts = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(meta, BorderLayout.SOUTH)
        }
        add(dot, BorderLayout.WEST)
        add(texts, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out TaskEntry>,
        value: TaskEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val task = value ?: return this

        background = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground
        isOpaque = true

        dot.color = colorOf(task)
        dot.pulsing = task.status == TaskStatus.RUNNING

        title.text = task.shortLabel().ifEmpty { "(빈 프롬프트)" }
        title.foreground = fg

        meta.text = metaOf(task)
        meta.foreground = if (isSelected) fg else JBColor.GRAY

        border = if (task.status == TaskStatus.RUNNING) {
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(RUNNING, 0, 2, 0, 0),
                JBUI.Borders.empty(4, 4),
            )
        } else {
            JBUI.Borders.empty(4, 6)
        }
        return this
    }

    /** 상태에 따라 다른 정보를 보여준다 — 완료는 끝난 시각·소요·비용, 실패는 이유 */
    private fun metaOf(task: TaskEntry): String {
        val parts = mutableListOf<String>()

        when (task.status) {
            TaskStatus.RUNNING -> {
                parts += when (task.finalState) {
                    SessionState.WAITING -> "응답 대기 중"
                    SessionState.WORKING -> "진행 중"
                    else -> "시작 중"
                }
            }

            TaskStatus.DONE -> {
                task.finishedAt?.let { parts += clock(it) }
                elapsed(task)?.let { parts += it }
                task.costUsd?.let { parts += "$%.3f".format(it) }
            }

            TaskStatus.FAILED, TaskStatus.CANCELED -> {
                task.finishedAt?.let { parts += clock(it) }
                task.errorMessage?.let { parts += it.take(50) }
            }

            TaskStatus.TODO, TaskStatus.QUEUED -> Unit
        }

        // 어느 claude 탭에서 돌았는지 — 여러 탭을 굴리면 이게 없으면 못 찾는다
        if (task.status != TaskStatus.TODO && task.terminalTab.isNotEmpty()) {
            parts += "⌗ ${task.terminalTab}"
        }
        if (task.attempts > 1) parts += "시도 ${task.attempts}회"
        return parts.joinToString("  ·  ")
    }

    /** 오늘 것은 시각만, 어제 이전은 날짜까지 */
    private fun clock(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val fmt = if (at.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) TIME else DATE_TIME
        return at.format(fmt)
    }

    private fun elapsed(task: TaskEntry): String? {
        val s = task.startedAt ?: return null
        val f = task.finishedAt ?: return null
        val sec = (f - s) / 1000.0
        return if (sec < 60) "%.1fs".format(sec) else "%d분 %ds".format((sec / 60).toInt(), (sec % 60).toInt())
    }

    private fun colorOf(task: TaskEntry): JBColor = when (task.status) {
        TaskStatus.TODO -> JBColor.GRAY
        TaskStatus.QUEUED -> QUEUED
        TaskStatus.RUNNING -> RUNNING
        TaskStatus.DONE -> DONE
        TaskStatus.FAILED -> FAILED
        TaskStatus.CANCELED -> JBColor.GRAY
    }

    /** 원형 상태 표시 */
    private class StatusDot : JPanel() {
        var color: JBColor = JBColor.GRAY
        var pulsing = false

        init {
            isOpaque = false
            preferredSize = JBUI.size(10, 10)
            minimumSize = preferredSize
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val d = JBUI.scale(8)
                val x = (width - d) / 2
                val y = (height - d) / 2
                g2.color = color
                if (pulsing) {
                    g2.fillOval(x, y, d, d)
                } else {
                    g2.drawOval(x, y, d - 1, d - 1)
                    g2.fillOval(x + 2, y + 2, d - 4, d - 4)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        // 라이트/다크 양쪽 값을 지정 — 테마에 따라 자동 선택된다
        private val QUEUED = JBColor(0x3574F0, 0x548AF7)
        private val RUNNING = JBColor(0x1F8B4C, 0x4CB782)
        private val DONE = JBColor(0x6E7781, 0x8B949E)
        private val FAILED = JBColor(0xD1383D, 0xE5534B)

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
    }
}
