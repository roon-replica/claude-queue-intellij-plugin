package dev.roon.taskqueue.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.queue.TaskEntry
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 칸반 컬럼 하나 — 헤더(제목+개수) + 카드 리스트 */
class QueueColumn(
    title: String,
    private val emptyHint: String,
) : JPanel(BorderLayout()) {

    val model = DefaultListModel<TaskEntry>()

    val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskCardRenderer()
        emptyText.text = emptyHint
    }

    private val header = JBLabel(title).apply {
        font = JBFont.smallOrNewUiMedium().asBold()
        border = JBUI.Borders.empty(4, 6)
    }

    private val baseTitle = title

    init {
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
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

    val selected: TaskEntry? get() = list.selectedValue
}
