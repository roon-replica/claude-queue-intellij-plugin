package dev.roon.taskqueue.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.queue.TaskEntry
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 칸반 컬럼 하나 — 헤더(제목+개수) + 카드 리스트 */
class QueueColumn(
    title: String,
    private val emptyHint: String,
    highlight: (TaskEntry) -> Float = { 0f },
) : JPanel(BorderLayout()) {

    val model = DefaultListModel<TaskEntry>()

    val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskCardRenderer(highlight)
        emptyText.text = emptyHint
    }

    private val header = JBLabel(title).apply {
        font = JBFont.smallOrNewUiMedium().asBold()
        border = JBUI.Borders.empty(4, 6)
    }

    /** 이 컬럼에만 해당되는 동작을 헤더 오른쪽에 둔다 (예: 완료 정리) */
    private val headerAction = JLabel().apply {
        border = JBUI.Borders.empty(2, 6)
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val baseTitle = title

    init {
        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.CENTER)
            add(headerAction, BorderLayout.EAST)
        }
        add(headerRow, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    /** 헤더 버튼 설정. [enabled] 가 false 면 흐리게 보이고 눌리지 않는다 */
    fun setHeaderAction(icon: Icon, tooltip: String, onClick: () -> Unit) {
        headerAction.icon = icon
        headerAction.toolTipText = tooltip
        headerAction.isVisible = true
        headerAction.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (headerAction.isEnabled) onClick()
            }
        })
    }

    fun setHeaderActionEnabled(enabled: Boolean) {
        headerAction.isEnabled = enabled
    }

    /** 목록 교체. 선택 항목은 id 로 복원한다 */
    fun setTasks(tasks: List<TaskEntry>) {
        val selectedId = list.selectedValue?.id
        model.clear()
        tasks.forEach { model.addElement(it) }
        header.text = if (tasks.isEmpty()) baseTitle else "$baseTitle  ${tasks.size}"

        if (selectedId != null) {
            val idx = tasks.indexOfFirst { it.id == selectedId }
            if (idx >= 0) list.selectedIndex = idx else list.clearSelection()
        }
    }

    fun clearSelection() = list.clearSelection()

    fun tasks(): List<TaskEntry> = (0 until model.size()).map { model.getElementAt(it) }

    /** 방금 옮겨온 카드를 화면에 보이게 — 컬럼이 길면 스크롤 밖에 있을 수 있다 */
    fun scrollTo(taskId: String) {
        val index = (0 until model.size()).firstOrNull { model.getElementAt(it).id == taskId } ?: return
        list.ensureIndexIsVisible(index)
    }

    val selected: TaskEntry? get() = list.selectedValue
}
