package dev.roon.taskqueue.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.git.GitDiffs
import dev.roon.taskqueue.nav.FileNavigator
import dev.roon.taskqueue.nav.FileRefs
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskQueueService
import dev.roon.taskqueue.queue.TaskStatus
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * 자동작업 큐 보드 — todo / 진행 / 완료 3컬럼 + 실행 로그 + 결과 참조.
 * 추가는 todo 로만 들어가고, ▶ 로 올릴 때 실행된다.
 */
class TaskQueuePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val cli = ClaudeCli.getInstance()
    private val queue = TaskQueueService.getInstance()

    private val promptField = JBTextField().apply {
        emptyText.text = "작업 내용 입력 후 Enter — todo 로 추가된다"
    }

    private val todoColumn = QueueColumn("TODO", "여기에 작업을 적어둔다")
    private val activeColumn = QueueColumn("진행", "▶ 로 올리면 여기서 순서대로 실행")
    private val doneColumn = QueueColumn("완료", "끝난 작업")
    private val columns = listOf(todoColumn, activeColumn, doneColumn)

    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        border = JBUI.Borders.empty(3, 6)
    }

    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        font = JBFont.small()
    }

    private val refsModel = DefaultListModel<FileRefs.Ref>()
    private val refsList = JBList(refsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "결과에 나온 file:line — 더블클릭으로 이동"
        cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? FileRefs.Ref)?.label() ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
    }

    private var cliInfo = "claude 확인 중…"

    private val queueListener: () -> Unit = { ui { refresh() } }
    private val logListener: (String) -> Unit = { line -> ui { appendLog(line) } }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        promptField.addActionListener { addTodo() }
        wireColumns()
        refsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedRef()
            }
        })

        queue.addListener(queueListener)
        queue.addLogListener(logListener)

        refreshCliStatus()
        queue.recentLog().forEach { appendLog(it) }
        refresh()
    }

    // --- 레이아웃 ---

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup().apply {
            add(action("▶ 실행", "선택한 todo 를 진행줄로", { selected()?.let { !it.status.isActive } == true }) {
                selected()?.let { queue.promote(it.id) }
            })
            add(action("▶▶ 전부", "todo 전부를 진행줄로", { queue.todos().isNotEmpty() }) {
                queue.runAllTodos()
            })
            add(action("↩ todo", "진행 대기 항목을 todo 로", { selected()?.status == TaskStatus.QUEUED }) {
                selected()?.let { queue.demote(it.id) }
            })
            addSeparator()
            add(action("■ 취소", "실행 중 작업 중단", { queue.runningTask() != null }) {
                queue.cancelRunning()
            })
            add(action("↻ 재시도", "실패/취소 항목 다시 실행", { selected()?.status?.isFinished == true }) {
                selected()?.let { queue.retry(it.id) }
            })
            addSeparator()
            add(action("↑", "순서 올리기", { selected() != null }) { selected()?.let { queue.move(it.id, -1) } })
            add(action("↓", "순서 내리기", { selected() != null }) { selected()?.let { queue.move(it.id, 1) } })
            addSeparator()
            add(action("일시정지/재개", "자동 진행 토글", { true }) { togglePause() })
            add(action("완료 정리", "완료·실패 항목 제거", { queue.tasks.any { it.status.isFinished } }) {
                queue.clearFinished()
            })
            add(action("변경분 diff", "HEAD 대비 변경 파일 보기", { true }) { showDiffChooser() })
            addSeparator()
            add(action("× 삭제", "항목 제거", { selected() != null }) { selected()?.let { queue.remove(it.id) } })
        }

        val toolbar: ActionToolbar = ActionManager.getInstance()
            .createActionToolbar("TaskQueue.Toolbar", group, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(promptField, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(2, 4)
        }
    }

    private fun buildBody(): OnePixelSplitter {
        val board = OnePixelSplitter(false, 0.34f).apply {
            firstComponent = todoColumn
            secondComponent = OnePixelSplitter(false, 0.5f).apply {
                firstComponent = activeColumn
                secondComponent = doneColumn
            }
        }

        val detail = OnePixelSplitter(true, 0.7f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(sectionLabel("실행 로그"), BorderLayout.NORTH)
                add(JBScrollPane(logArea), BorderLayout.CENTER)
            }
            secondComponent = JPanel(BorderLayout()).apply {
                add(sectionLabel("결과 파일"), BorderLayout.NORTH)
                add(JBScrollPane(refsList), BorderLayout.CENTER)
            }
        }

        return OnePixelSplitter(false, 0.62f).apply {
            firstComponent = board
            secondComponent = detail
        }
    }

    private fun sectionLabel(text: String) = JBLabel(text, SwingConstants.LEFT).apply {
        font = JBFont.smallOrNewUiMedium().asBold()
        border = JBUI.Borders.empty(4, 6)
    }

    /** 한 컬럼에서 고르면 나머지 선택을 지운다 — 선택은 항상 하나 */
    private fun wireColumns() {
        columns.forEach { column ->
            column.list.addListSelectionListener { e ->
                if (e.valueIsAdjusting) return@addListSelectionListener
                if (column.selected != null) {
                    columns.filter { it !== column }.forEach { it.clearSelection() }
                }
                refreshRefs()
            }
            // 더블클릭 = 컬럼 간 이동 (todo→진행, 진행→todo)
            column.list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val task = column.selected ?: return
                    when (task.status) {
                        TaskStatus.TODO -> queue.promote(task.id)
                        TaskStatus.QUEUED -> queue.demote(task.id)
                        else -> Unit
                    }
                }
            })
        }
    }

    // --- 동작 ---

    private fun selected(): TaskEntry? = columns.firstNotNullOfOrNull { it.selected }

    private fun addTodo() {
        val prompt = promptField.text?.trim().orEmpty()
        if (prompt.isEmpty()) return
        val cwd = project.basePath ?: File(System.getProperty("user.home")).absolutePath
        queue.addTodo(prompt, cwd)
        promptField.text = ""
    }

    private fun togglePause() {
        if (queue.autoAdvance) queue.pause() else queue.start()
        refresh()
    }

    /** 프로세스 실행은 EDT 금지 — 백그라운드에서 조회하고 결과만 EDT 로 */
    private fun refreshCliStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val exe = cli.findExecutable()
            val version = if (exe != null) cli.version() else null
            ui {
                if (exe == null) {
                    cliInfo = "claude CLI 없음 — 설치 후 IDE 재시작"
                    promptField.isEnabled = false
                } else {
                    cliInfo = "claude ${version ?: "?"}"
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        val all = queue.tasks
        todoColumn.setTasks(all.filter { it.status == TaskStatus.TODO })
        activeColumn.setTasks(all.filter { it.status.isActive })
        doneColumn.setTasks(all.filter { it.status.isFinished })

        statusLabel.text = buildString {
            append(if (queue.autoAdvance) "자동 진행 ON" else "일시정지")
            queue.runningTask()?.let { append("  ·  실행: ${it.shortLabel()}") }
            append("  ·  ").append(project.basePath?.let(::File)?.name ?: "-")
            append("  ·  ").append(cliInfo)
        }

        refreshRefs()
    }

    private fun refreshRefs() {
        val task = selected() ?: queue.runningTask()
        val refs = task?.let { queue.refs(it.id) } ?: emptyList()
        if (refsModel.elements().toList() == refs) return
        refsModel.clear()
        refs.forEach { refsModel.addElement(it) }
    }

    private fun openSelectedRef() {
        val ref = refsList.selectedValue ?: return
        val task = selected() ?: queue.runningTask() ?: return
        val file = FileRefs.resolve(ref, File(task.cwd))
        if (!FileNavigator.open(project, file, ref.line)) {
            statusLabel.text = "열 수 없음: ${file.absolutePath}"
        }
    }

    private fun showDiffChooser() {
        val task = selected() ?: queue.runningTask()
        val cwd = File(task?.cwd ?: project.basePath ?: return)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (!GitDiffs.isGitRepo(cwd)) {
                ui { statusLabel.text = "git repo 가 아니다: ${cwd.absolutePath}" }
                return@executeOnPooledThread
            }
            val files = GitDiffs.changedFiles(cwd)
            ui {
                if (files.isEmpty()) {
                    statusLabel.text = "변경분 없음 (HEAD 대비)"
                    return@ui
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(files)
                    .setTitle("변경분 (${files.size}개)")
                    .setItemChosenCallback { path -> showDiff(cwd, path) }
                    .createPopup()
                    .showInCenterOf(this)
            }
        }
    }

    private fun showDiff(cwd: File, path: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val before = GitDiffs.contentAtHead(cwd, path)
            ui {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(cwd, path))
                if (vf == null) {
                    statusLabel.text = "파일을 찾을 수 없다: $path"
                    return@ui
                }
                val factory = DiffContentFactory.getInstance()
                val left = if (before != null) factory.create(project, before, vf.fileType)
                else factory.createEmpty()
                val right = factory.create(project, vf)
                DiffManager.getInstance().showDiff(
                    project,
                    SimpleDiffRequest(path, left, right, if (before != null) "HEAD" else "(신규)", "작업 후"),
                )
            }
        }
    }

    /** 원시 stream-json 대신 사람이 읽는 한 줄로 */
    private fun appendLog(line: String) {
        val text = LogFormatter.format(line) ?: return
        logArea.append(text + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    /** 조건부 활성 액션을 짧게 만드는 헬퍼 */
    private fun action(text: String, description: String, enabled: () -> Boolean, run: () -> Unit): AnAction =
        object : AnAction(text, description, null) {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = run()
        }

    override fun dispose() {
        queue.removeListener(queueListener)
        queue.removeLogListener(logListener)
    }
}
