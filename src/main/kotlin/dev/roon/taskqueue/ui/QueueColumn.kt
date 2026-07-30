package dev.roon.taskqueue.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.queue.TaskEntry
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * 칸반 컬럼 하나 — 헤더(제목+개수) + 카드 리스트.
 * @param accent 헤더 색. 카드 상태점과 같은 값을 써야 둘의 연결이 읽힌다
 */
class QueueColumn(
    title: String,
    private val emptyHint: String,
    highlight: (TaskEntry) -> Float = { 0f },
    accent: JBColor = StatusColors.TODO,
) : JPanel(BorderLayout()) {

    val model = DefaultListModel<TaskEntry>()

    /** 마우스가 올라간 행 — 그 카드에만 조작 아이콘을 띄운다 */
    private var hoverIndex = -1

    val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskCardRenderer(highlight) { it == hoverIndex }
        emptyText.text = emptyHint
    }

    private val header = JBLabel(title).apply {
        font = JBFont.smallOrNewUiMedium().asBold()
        border = JBUI.Borders.empty(6, 6)
        foreground = accent
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
            // 헤더와 카드 목록을 가르는 선 — 컬럼 경계가 눈에 잡힌다
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(header, BorderLayout.CENTER)
            add(headerAction, BorderLayout.EAST)
        }
        add(headerRow, BorderLayout.NORTH)
        val scroll = JBScrollPane(list).apply {
            // 가로 스크롤을 두면 카드가 옆으로 늘어나 오른쪽 버튼이 보이는 영역 밖으로 나간다
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
        trackHover()
        reflowOnResize()
    }

    /**
     * 컬럼 폭이 바뀌면 줄바꿈 위치가 달라져 카드 높이도 달라진다.
     * JList 는 셀 높이를 캐시하므로 다시 재게 만들어야 글자가 잘리지 않는다.
     */
    private fun reflowOnResize() {
        list.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                list.fixedCellHeight = 1
                list.fixedCellHeight = -1
            }
        })
    }

    /** 바뀐 두 행만 다시 그린다 — 리스트 전체 repaint 는 낭비다 */
    private fun trackHover() {
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHover(rowAt(e))
            override fun mouseDragged(e: MouseEvent) = setHover(rowAt(e))
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) = setHover(-1)
        })
    }

    /** 그 행에 조작 아이콘이 실제로 보이는지 — 안 보이는 버튼이 눌리면 안 된다 */
    fun actionsVisible(index: Int): Boolean = index == hoverIndex || index == list.selectedIndex

    private fun rowAt(e: MouseEvent): Int {
        val index = list.locationToIndex(e.point)
        if (index < 0) return -1
        return if (list.getCellBounds(index, index)?.contains(e.point) == true) index else -1
    }

    private fun setHover(index: Int) {
        if (index == hoverIndex) return
        val previous = hoverIndex
        hoverIndex = index
        listOf(previous, index).filter { it >= 0 }.forEach { row ->
            list.getCellBounds(row, row)?.let { list.repaint(it) }
        }
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
