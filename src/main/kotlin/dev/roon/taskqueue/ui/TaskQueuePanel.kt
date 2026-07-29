package dev.roon.taskqueue.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
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
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.git.GitDiffs
import dev.roon.taskqueue.nav.FileNavigator
import dev.roon.taskqueue.nav.FileRefs
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskQueueService
import dev.roon.taskqueue.queue.TaskStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 자동작업 큐 보드 + 실행 로그 */
class TaskQueuePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val cli = ClaudeCli.getInstance()
    private val queue = TaskQueueService.getInstance()

    private val promptField = JBTextField()
    private val addButton = JButton("큐에 추가")
    private val cancelButton = JButton("실행 취소")
    private val retryButton = JButton("재시도")
    private val removeButton = JButton("삭제")
    private val upButton = JButton("↑")
    private val downButton = JButton("↓")
    private val pauseButton = JButton("일시정지")
    private val clearButton = JButton("완료 정리")
    private val diffButton = JButton("변경분 diff")

    private val refsModel = DefaultListModel<FileRefs.Ref>()
    private val refsList = JBList(refsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "결과에서 찾은 file:line 이 여기 표시된다"
    }

    private val statusLabel = JBLabel()
    private val listModel = DefaultListModel<TaskEntry>()
    private val taskList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskCellRenderer()
    }
    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
    }

    /** CLI 탐지 결과 — 비동기로 채워진다 */
    private var cliInfo: String = "claude 확인 중…"

    private val queueListener: () -> Unit = { ui { refresh() } }
    private val logListener: (String) -> Unit = { line -> ui { appendLog(line) } }

    init {
        border = JBUI.Borders.empty(8)

        val inputRow = JPanel(BorderLayout(8, 0)).apply {
            add(promptField, BorderLayout.CENTER)
            add(addButton, BorderLayout.EAST)
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(cancelButton); add(retryButton); add(removeButton)
            add(upButton); add(downButton)
            add(pauseButton); add(clearButton); add(diffButton)
        }

        add(JPanel(BorderLayout()).apply {
            add(inputRow, BorderLayout.NORTH)
            add(buttonRow, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)

        add(
            OnePixelSplitter(false, 0.35f).apply {
                firstComponent = JBScrollPane(taskList)
                secondComponent = OnePixelSplitter(true, 0.65f).apply {
                    firstComponent = JBScrollPane(logArea)
                    secondComponent = JBScrollPane(refsList)
                }
            },
            BorderLayout.CENTER,
        )

        addButton.addActionListener { enqueue() }
        promptField.addActionListener { enqueue() }
        cancelButton.addActionListener { queue.cancelRunning() }
        retryButton.addActionListener { selected()?.let { queue.retry(it.id) } }
        removeButton.addActionListener { selected()?.let { queue.remove(it.id) } }
        upButton.addActionListener { selected()?.let { queue.move(it.id, -1) } }
        downButton.addActionListener { selected()?.let { queue.move(it.id, 1) } }
        clearButton.addActionListener { queue.clearFinished() }
        pauseButton.addActionListener { togglePause() }
        diffButton.addActionListener { showDiffChooser() }

        taskList.addListSelectionListener { refreshRefs() }
        refsList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) openSelectedRef()
            }
        })

        queue.addListener(queueListener)
        queue.addLogListener(logListener)

        refreshCliStatus()
        queue.recentLog().forEach { appendLog(it) }
        refresh()
    }

    private fun selected(): TaskEntry? = taskList.selectedValue

    private fun enqueue() {
        val prompt = promptField.text?.trim().orEmpty()
        if (prompt.isEmpty()) return
        val cwd = project.basePath ?: File(System.getProperty("user.home")).absolutePath
        queue.enqueue(prompt, cwd)
        promptField.text = ""
    }

    private fun togglePause() {
        if (queue.autoAdvance) queue.pause() else queue.start()
        refresh()
    }

    /**
     * CLI 미설치면 큐 추가를 막고 안내한다.
     * 프로세스 실행은 EDT 에서 금지 — pooled thread 로 돌리고 결과만 EDT 에서 반영한다.
     */
    private fun refreshCliStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val exe = cli.findExecutable()
            val version = if (exe != null) cli.version() else null
            ui {
                if (exe == null) {
                    statusLabel.text = "claude CLI 를 찾을 수 없다 — 설치 후 IDE 재시작"
                    addButton.isEnabled = false
                    promptField.isEnabled = false
                } else {
                    cliInfo = "claude ${version ?: "?"}"
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val selectedId = selected()?.id
        listModel.clear()
        queue.tasks.forEach { listModel.addElement(it) }
        selectedId?.let { id ->
            queue.tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { taskList.selectedIndex = it }
        }

        val running = queue.runningTask()
        val queuedCount = queue.queued().size
        pauseButton.text = if (queue.autoAdvance) "일시정지" else "재개"
        cancelButton.isEnabled = running != null

        statusLabel.text = buildString {
            append(if (queue.autoAdvance) "진행중" else "일시정지")
            append("  ·  대기 $queuedCount")
            running?.let {
                append("  ·  실행: ${it.shortLabel()}")
                append(" [${it.finalState}]")
            }
            append("  ·  cwd: ${project.basePath ?: "-"}")
            append("  ·  $cliInfo")
        }

        refreshRefs()
    }

    /** 선택 항목의 결과 참조 목록 갱신. 선택이 없으면 실행 중 작업 기준 */
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

    /** 선택 작업의 cwd 기준 변경 파일 목록 → 고르면 IntelliJ diff 뷰어 */
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
                    .showUnderneathOf(diffButton)
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

    private fun appendLog(line: String) {
        // 원시 stream-json 은 길어서 요약만 — 상세는 IDE 로그/파일로
        logArea.append(summarize(line) + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun summarize(line: String): String {
        if (line.length <= LOG_LINE_MAX) return line
        return line.take(LOG_LINE_MAX) + "…"
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    override fun dispose() {
        queue.removeListener(queueListener)
        queue.removeLogListener(logListener)
    }

    private class TaskCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val task = value as? TaskEntry
            val text = task?.let {
                val chip = when (it.status) {
                    TaskStatus.QUEUED -> "대기"
                    TaskStatus.RUNNING -> "작업중"
                    TaskStatus.DONE -> "완료"
                    TaskStatus.FAILED -> "실패"
                    TaskStatus.CANCELED -> "취소"
                }
                val cost = it.costUsd?.let { c -> "  $%.3f".format(c) } ?: ""
                val err = it.errorMessage?.let { e -> "  ($e)" } ?: ""
                val ctx = if (it.status == TaskStatus.RUNNING) "  ${it.finalState}" else ""
                "[$chip] ${it.shortLabel()}$ctx$cost$err"
            } ?: value?.toString()
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }

    companion object {
        private const val LOG_LINE_MAX = 300
    }
}
