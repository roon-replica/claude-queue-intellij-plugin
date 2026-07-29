package dev.roon.taskqueue.ui

import com.intellij.ui.components.JBList
import dev.roon.taskqueue.queue.TaskEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.TransferHandler

/**
 * 카드 드래그앤드롭. 버튼(위/아래/되돌리기) 대신 이걸로 옮긴다.
 *
 * 같은 컬럼 안 = 순서 변경, 다른 컬럼 = 상태 이동.
 * 작업 식별은 id 문자열로 한다 — 커스텀 DataFlavor 는 드래그 중 클래스로더가 달라 깨질 수 있다.
 */
object TaskDragAndDrop {

    private const val PREFIX = "taskqueue-id:"

    /**
     * @param onReorder 같은 컬럼 안에서 놓았을 때 — 그 컬럼의 새 순서(id 목록)
     * @param onMove    다른 컬럼에 놓았을 때 — (옮긴 작업, 목표 컬럼)
     */
    fun install(
        columns: List<QueueColumn>,
        onReorder: (List<String>) -> Unit,
        onMove: (TaskEntry, QueueColumn) -> Unit,
        fixed: Set<QueueColumn> = emptySet(),
    ) {
        columns.forEach { column ->
            // 끝난 작업은 순서도 상태도 의미가 없다 — 끌 수도, 받을 수도 없게 한다
            column.list.dragEnabled = column !in fixed
            column.list.dropMode = DropMode.INSERT
            if (column !in fixed) {
                column.list.transferHandler = Handler(columns, column, onReorder, onMove)
            }
        }
    }

    private class Handler(
        private val columns: List<QueueColumn>,
        private val target: QueueColumn,
        private val onReorder: (List<String>) -> Unit,
        private val onMove: (TaskEntry, QueueColumn) -> Unit,
    ) : TransferHandler() {

        override fun getSourceActions(c: JComponent) = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val id = (c as? JBList<*>)?.selectedValue?.let { (it as? TaskEntry)?.id } ?: return null
            return StringSelection(PREFIX + id)
        }

        override fun canImport(support: TransferSupport): Boolean =
            support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

        override fun importData(support: TransferSupport): Boolean {
            val id = draggedId(support) ?: return false
            val source = columns.firstOrNull { col -> col.tasks().any { it.id == id } } ?: return false
            val task = source.tasks().first { it.id == id }

            if (source !== target) {
                onMove(task, target)
                return true
            }

            val dropIndex = (support.dropLocation as? JList.DropLocation)?.index ?: return false
            onReorder(reordered(source.tasks().map { it.id }, id, dropIndex))
            return true
        }

        private fun draggedId(support: TransferSupport): String? {
            val text = runCatching {
                support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull() ?: return null
            return text.removePrefix(PREFIX).takeIf { it != text && it.isNotEmpty() }
        }

        /** 드롭 인덱스는 **제거 전** 목록 기준이라, 뒤로 옮길 때 한 칸 보정이 필요하다 */
        private fun reordered(ids: List<String>, moving: String, dropIndex: Int): List<String> {
            val from = ids.indexOf(moving)
            val to = if (dropIndex > from) dropIndex - 1 else dropIndex
            val rest = ids.toMutableList().also { it.removeAt(from) }
            rest.add(to.coerceIn(0, rest.size), moving)
            return rest
        }
    }
}
