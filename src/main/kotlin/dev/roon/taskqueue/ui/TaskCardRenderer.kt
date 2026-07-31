package dev.roon.taskqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskStatus
import dev.roon.taskqueue.session.SessionState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
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
import javax.swing.Icon
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * 카드 = 상태점 + 프롬프트 1줄 + 메타 1줄.
 * @param highlight 방금 상태가 바뀐 카드의 잔상 세기 (0f = 없음)
 * @param retryable ↻ 를 띄울 항목인지. **좌표 판정과 같은 판단을 써야 한다** —
 *   어긋나면 안 보이는 버튼이 눌리거나 보이는 버튼이 안 먹는다
 */
class TaskCardRenderer(
    private val highlight: (TaskEntry) -> Float = { 0f },
    private val hovered: (Int) -> Boolean = { false },
    private val retryable: (TaskEntry) -> Boolean = { false },
    /** 실행 중 카드 테두리의 맥동 세기 (0..1). 패널이 주기적으로 올려준다 */
    private val pulse: () -> Float = { 0f },
) : JPanel(BorderLayout(JBUI.scale(6), 0)), ListCellRenderer<TaskEntry> {

    private val dot = StatusDot()
    private val title = JLabel()
    private val meta = JLabel().apply {
        font = JBFont.small()
    }

    /** 카드를 그릴 컬럼 폭 — 긴 메타/요약이 카드를 옆으로 늘리지 못하게 상한으로 쓴다 */
    private var cellWidth = 0

    /** claude 의 마지막 답변 — 터미널을 열지 않고도 결과를 알 수 있게 */
    private val summary = JLabel().apply {
        font = JBFont.small()
    }

    /** 카드에서 바로 누르는 버튼들. 실제 클릭 판정은 리스트가 좌표로 한다 */
    private val edit = iconButton(AllIcons.Actions.Edit, "Edit this task")
    private val run = iconButton(AllIcons.Actions.Execute, "Run this task")
    private val close = iconButton(AllIcons.Actions.Close, "Delete this task")

    /** 멈춰 보이는 작업을 다시 돌리는 통로 — 실행 중이면 재전송, 대기 중이면 지금 실행 */
    private val retry = iconButton(AllIcons.Actions.Restart, "Run this task now")

    private fun iconButton(icon: Icon, tooltip: String) = JLabel(icon).apply {
        preferredSize = JBUI.size(ACTION_WIDTH, 16)
        horizontalAlignment = SwingConstants.CENTER
        toolTipText = tooltip
    }

    init {
        border = JBUI.Borders.empty(CARD_PAD_V, 6)
        val texts = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(meta, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
        // FlowLayout 은 숨긴 컴포넌트를 건너뛴다 — todo 가 아니면 ✕ 만 오른쪽에 남는다.
        // 버튼은 4개지만 동시에 보이는 건 최대 3개(todo: ▶✎✕, 실행중: ↻✕)라 폭 계산은 그대로다
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(run)
            add(edit)
            add(retry)
            add(close)
        }
        add(dot, BorderLayout.WEST)
        add(texts, BorderLayout.CENTER)
        add(actions, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out TaskEntry>,
        value: TaskEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val task = value ?: return this

        val isHover = hovered(index)
        val plain = if (isSelected) list.selectionBackground else list.background
        // 마우스가 올라간 행을 살짝 띄운다 — 어떤 카드를 조작하는지 분명해진다
        val base = if (isSelected || !isHover) plain else blend(plain, list.foreground, HOVER_MIX)
        val fg = if (isSelected) list.selectionForeground else list.foreground
        // 방금 옮겨온 카드는 테두리로만 표시한다 — 배경까지 물들이면 글자 대비가 흔들린다
        background = base
        isOpaque = true

        dot.color = StatusColors.of(task.status)
        dot.pulsing = task.status == TaskStatus.RUNNING

        // 아이콘은 마우스가 올라갔거나 선택된 카드에만 — 항상 띄우면 어수선하다
        val showActions = isHover || isSelected
        val isTodo = task.status == TaskStatus.TODO
        run.isVisible = isTodo && showActions
        edit.isVisible = isTodo && showActions
        retry.isVisible = showActions && retryable(task)
        close.isVisible = showActions

        val label = task.excerpt(CARD_LABEL_MAX).ifEmpty { "(empty prompt)" }
        // 실행 순서가 의미 있는 항목에만 번호 — 돌고 있거나 끝난 건 순서가 무의미하다
        val heading = if (task.status.isOrdered) "${index + 1}. $label" else label
        // 컬럼 폭에 맞춰 여러 줄로 접는다. 한 줄로 두면 카드가 옆으로 늘어나 오른쪽
        // 버튼이 보이는 영역 밖으로 밀려나 클릭할 수 없었다
        title.text = wrapped(heading, textWidth(list))
        title.toolTipText = task.prompt
        // 끝난 작업은 흐리게 — 지금 돌거나 앞으로 돌 작업에 시선이 가게
        title.foreground = if (task.status.isFinished && !isSelected) JBColor.GRAY else fg

        // 결과 요약은 있을 때만 한 줄 차지한다
        val result = task.summary?.let { oneLine(it) }
        summary.isVisible = result != null && task.status.isFinished
        summary.text = result?.let { "↳ ${it.take(SUMMARY_MAX)}" } ?: ""
        summary.foreground = if (isSelected) fg else JBColor.GRAY
        summary.toolTipText = task.summary

        cellWidth = list.width
        meta.text = metaOf(task)
        meta.foreground = when {
            isSelected -> fg
            // 실패 이유는 회색이면 중요하지 않은 정보로 읽힌다
            task.status == TaskStatus.FAILED -> StatusColors.FAILED
            else -> JBColor.GRAY
        }

        border = borderFor(task, base)
        return this
    }

    /**
     * 카드 테두리.
     *
     * 방금 상태가 바뀐 카드는 **바깥 1px 테두리**로 알린다. 색을 배경 쪽으로 섞어
     * 잔상이 사라지게 한다 — 알파를 쓰지 않고 섞는 이유는 리스트 배경과 자연스럽게
     * 맞물려야 하기 때문이다(StatusColors.blend 와 같은 이유).
     *
     * 안쪽은 원래 여백이고, 실행 중이면 왼쪽 굵은 선이 그대로 남는다 —
     * 테두리를 겹쳐 쓰므로 두 표시가 서로를 지우지 않는다. 바깥 선이 1px 늘어난 만큼
     * 안쪽 여백을 1px 줄여 카드 크기가 흔들리지 않게 한다.
     */
    private fun borderFor(task: TaskEntry, base: Color): javax.swing.border.Border {
        val glow = highlight(task)
        val running = task.status == TaskStatus.RUNNING

        // 실행 중이면 전체를 감싸는 테두리가 숨을 쉰다 — 어느 카드가 지금 도는지 멀리서도 보인다.
        // 굵기는 고정이고 색만 바뀐다: 굵기가 흔들리면 목록이 들썩인다
        val inner = if (running) {
            val breath = PULSE_MIN + (1f - PULSE_MIN) * pulse()
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(blend(base, StatusColors.RUNNING, breath), 1),
                JBUI.Borders.empty(CARD_PAD_V - 1, if (glow > 0f) 4 else 5),
            )
        } else {
            JBUI.Borders.empty(CARD_PAD_V - if (glow > 0f) 1 else 0, if (glow > 0f) 5 else 6)
        }
        if (glow <= 0f) return inner
        val edge = blend(base, StatusColors.of(task.status), glow)
        return BorderFactory.createCompoundBorder(JBUI.Borders.customLine(edge, 1), inner)
    }

    /** 카드는 컬럼보다 넓어질 수 없다 — 넘치면 가로 스크롤이 생겨 버튼이 화면 밖으로 나간다 */
    override fun getPreferredSize() = super.getPreferredSize().also {
        if (cellWidth > 0) it.width = minOf(it.width, cellWidth)
    }

    /** 상태에 따라 다른 정보를 보여준다 — 완료는 끝난 시각·소요·비용, 실패는 이유 */
    private fun metaOf(task: TaskEntry): String {
        val parts = mutableListOf<String>()

        when (task.status) {
            TaskStatus.RUNNING -> {
                parts += when (task.finalState) {
                    SessionState.WAITING -> "waiting for input"
                    SessionState.WORKING -> "working"
                    else -> "starting"
                }
                // 경과 시간이 없으면 멈춘 건지 오래 걸리는 건지 구분이 안 된다
                task.startedAt?.let { parts += duration(System.currentTimeMillis() - it) }
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
        if (task.attempts > 1) parts += "attempt ${task.attempts}"
        return parts.joinToString("  ·  ")
    }

    /**
     * 제목에 쓸 수 있는 폭. 버튼은 실제로 보일 때만 자리를 차지하지만, 폭을 hover 에 따라
     * 바꾸면 마우스만 올려도 줄바꿈이 흔들린다 — 항상 세 칸을 비워 둔다.
     */
    private fun textWidth(list: JList<out TaskEntry>): Int {
        val fixed = JBUI.scale(10 + 6 + 12) + 3 * JBUI.scale(ACTION_WIDTH)
        return (list.width - fixed).coerceAtLeast(JBUI.scale(80))
    }

    /** JLabel 은 HTML 에 폭을 주면 그 안에서 접는다 — 특수문자는 태그로 새지 않게 막는다 */
    private fun wrapped(text: String, width: Int): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><div width=\"$width\">$safe</div></html>"
    }

    /** 답변은 여러 줄일 수 있다 — 카드는 한 줄만 쓴다 */
    private fun oneLine(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /** 오늘 것은 시각만, 어제 이전은 날짜까지 */
    private fun clock(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val fmt = if (at.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) TIME else DATE_TIME
        return at.format(fmt)
    }

    private fun elapsed(task: TaskEntry): String? {
        val s = task.startedAt ?: return null
        val f = task.finishedAt ?: return null
        return duration(f - s)
    }

    /** 1분 미만은 초만, 넘으면 분+초. 실행 중에는 1초마다 갱신되므로 소수점을 쓰지 않는다 */
    private fun duration(millis: Long): String {
        val sec = (millis / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }

    private fun blend(base: Color, tint: Color, ratio: Float) = StatusColors.blend(base, tint, ratio)

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

        /**
         * 카드에 보일 프롬프트 길이. 컬럼이 좁아도 줄바꿈으로 접히니
         * 60자(`shortLabel`)는 너무 짧아 무슨 작업인지 안 읽혔다. 전문은 툴팁에 있다.
         */
        private const val CARD_LABEL_MAX = 160

        /** 맥동의 가장 옅은 지점 — 0 이면 테두리가 사라져 카드가 깜빡이는 것처럼 보인다 */
        private const val PULSE_MIN = 0.35f

        /** 카드 세로 여백 — 너무 좁으면 목록이 답답하다 */
        private const val CARD_PAD_V = 7

        /** 카드 오른쪽 버튼 하나의 폭 — 클릭 판정도 이 값을 쓴다. 좁으면 정밀 조준이 필요하다 */
        const val ACTION_WIDTH = 28

        /** 잔상이 가장 진할 때의 혼합 비율 — 글자가 묻히지 않을 만큼만 */
        /** 마우스 올린 행의 강조 — 아주 옅게 */
        private const val HOVER_MIX = 0.07f

        /** 카드에 싣는 답변 길이. 전문은 툴팁으로 본다 */
        private const val SUMMARY_MAX = 90

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
    }
}
