package dev.roon.taskqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.queue.ExecMode
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskQueueService
import dev.roon.taskqueue.queue.TaskStatus
import dev.roon.taskqueue.terminal.TerminalTabs
import dev.roon.taskqueue.terminal.TerminalTabFocuser
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JPanel

/**
 * 자동작업 큐 보드 — todo / 진행 / 완료 3컬럼.
 * 추가는 todo 로만 들어가고, 진행으로 옮길 때(드래그 또는 ▶) 실행된다.
 * claude 출력은 터미널 탭에 그대로 있으므로 여기서 다시 보여주지 않는다.
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

    /** 상태줄에 띄울 마지막 진행 메시지 — 전체는 툴바 '로그' 로 본다 */
    private var lastLog = ""

    private var cliInfo = "claude 확인 중…"

    private val queueListener: () -> Unit = { ui { refresh() } }
    private val logListener: (String) -> Unit = { line -> ui { appendLog(line) } }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        promptField.addActionListener { addTodo() }
        wireColumns()
        queue.addListener(queueListener)
        queue.addLogListener(logListener)

        refreshCliStatus()
        queue.recentLog().lastOrNull()?.let { appendLog(it) }
        refresh()
    }

    // --- 레이아웃 ---

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup().apply {
            add(action("실행", "선택한 todo 를 진행줄로 올린다", AllIcons.Actions.Execute,
                { selected()?.let { !it.status.isActive } == true }) {
                selected()?.let { task -> chooseTerminal { tab -> queue.promote(task.id, tab) } }
            })
            add(action("todo 전부 실행", "todo 전부를 진행줄로", AllIcons.Actions.RunAll,
                { queue.todos().isNotEmpty() }) {
                chooseTerminal { tab -> queue.runAllTodos(tab) }
            })
            addSeparator()
            add(action("터미널 보기", "이 작업이 도는 claude 탭으로 이동", AllIcons.Actions.MoveTo2,
                { selected()?.terminalTab?.isNotEmpty() == true }) {
                selected()?.let { focusTerminal(it) }
            })
            add(action("실행 중단","실행 중 작업 취소", AllIcons.Actions.Suspend,
                { queue.runningTask() != null }) {
                queue.cancelRunning()
            })
            add(action("재시도", "실패·취소 항목 다시 실행", AllIcons.Actions.Restart,
                { selected()?.status?.isFinished == true }) {
                selected()?.let { queue.retry(it.id) }
            })
            addSeparator()
            add(PauseResumeAction())
            addSeparator()
            add(action("완료 정리", "완료·실패 항목 제거", AllIcons.Actions.GC,
                { queue.tasks.any { it.status.isFinished } }) {
                queue.clearFinished()
            })
            add(action("삭제", "선택 항목 제거", AllIcons.General.Remove, { selected() != null }) {
                selected()?.let { queue.remove(it.id) }
            })
            addSeparator()
            add(action("로그", "플러그인 진행 로그 (claude 출력은 터미널 탭에 있다)",
                AllIcons.Debugger.Console, { true }) { showLog() })
        }

        val toolbar: ActionToolbar = ActionManager.getInstance()
            .createActionToolbar("TaskQueue.Toolbar", group, true)
        toolbar.targetComponent = this

        val inputRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(promptField, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(inputRow, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(2, 4)
        }
    }

    /** 보드가 화면을 다 쓴다 — 실제 출력은 claude 터미널 탭에 있으므로 로그 패널을 두지 않는다 */
    private fun buildBody(): OnePixelSplitter = OnePixelSplitter(false, 0.34f).apply {
        firstComponent = todoColumn
        secondComponent = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = activeColumn
            secondComponent = doneColumn
        }
    }

    /** 한 컬럼에서 고르면 나머지 선택을 지운다 — 선택은 항상 하나 */
    private fun wireColumns() {
        columns.forEach { column ->
            column.list.addListSelectionListener { e ->
                if (e.valueIsAdjusting) return@addListSelectionListener
                if (column.selected != null) {
                    columns.filter { it !== column }.forEach { it.clearSelection() }
                }
            }
            // 더블클릭 = 컬럼 간 이동 (드래그앤드롭과 같은 동작, 키보드/빠른 조작용)
            column.list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val task = column.selected ?: return
                    when (task.status) {
                        TaskStatus.TODO -> chooseTerminal { tab -> queue.promote(task.id, tab) }
                        TaskStatus.QUEUED -> queue.demote(task.id)
                        // 실행 중이면 그 claude 터미널로 보내준다 — 개입하려면 그게 필요하다
                        TaskStatus.RUNNING -> focusTerminal(task)
                        else -> Unit
                    }
                }
            })
        }

        TaskDragAndDrop.install(columns, queue::reorderGroup, ::dropTo, fixed = setOf(doneColumn))
    }

    /** 다른 컬럼에 떨어뜨렸을 때의 상태 이동 */
    private fun dropTo(task: TaskEntry, target: QueueColumn) = when {
        target === todoColumn && task.status == TaskStatus.QUEUED -> queue.demote(task.id)
        target === activeColumn && task.status == TaskStatus.TODO ->
            chooseTerminal { tab -> queue.promote(task.id, tab) }
        // 완료 컬럼으로 끌어오거나, 실행 중 항목을 옮기는 건 허용하지 않는다
        else -> Unit
    }

    // --- 동작 ---

    private fun selected(): TaskEntry? = columns.firstNotNullOfOrNull { it.selected }

    private fun addTodo() {
        val prompt = promptField.text?.trim().orEmpty()
        if (prompt.isEmpty()) return
        queue.addTodo(prompt, cwd())
        promptField.text = ""
    }


    private fun focusTerminal(task: TaskEntry) {
        if (!TerminalTabFocuser.focus(project, task.terminalTab)) {
            appendLog("· 터미널 탭을 찾을 수 없다: ${task.terminalTab.ifEmpty { "(없음)" }}")
        }
    }

    private fun cwd(): String =
        project.basePath ?: File(System.getProperty("user.home")).absolutePath

    /**
     * 실행할 터미널을 팔레트로 고른다. 열린 탭이 없으면 묻지 않고 새 탭으로 진행.
     * @param onChosen 선택된 탭 이름 ("" = 새 탭)
     */
    private fun chooseTerminal(onChosen: (String) -> Unit) {
        val tabs = TerminalTabs.list(project)
        if (tabs.isEmpty()) {
            onChosen("")
            return
        }

        val items = listOf(NEW_TERMINAL) + tabs.map { it.display }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("실행할 터미널")
            .setMovable(false)
            .setResizable(false)
            .setItemChosenCallback { chosen ->
                // 고른 탭을 레지스트리에 고정한다 — 제목이 바뀌어도 위젯 참조로 찾는다
                val tab = tabs.firstOrNull { it.display == chosen }?.let(TerminalTabs::pin) ?: ""
                onChosen(tab)
            }
            .createPopup()
            .showInBestPositionFor(DataManager.getInstance().getDataContext(this))
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
            if (lastLog.isNotEmpty()) append("  ·  ").append(lastLog.take(80))
        }
    }



    /**
     * stream-json 은 사람이 읽는 한 줄로 바꾸고, 그 외(러너가 남긴 진행 메시지)는 그대로 표시한다.
     * 전부 LogFormatter 로 넘기면 JSON 이 아닌 줄이 조용히 버려진다.
     */
    private fun appendLog(line: String) {
        val isJson = line.trimStart().startsWith("{")
        lastLog = if (isJson) LogFormatter.format(line) ?: return else line
        refresh()
    }

    /** 문제 생겼을 때만 열어 보는 창 — 평소엔 화면을 차지하지 않는다 */
    private fun showLog() {
        val lines = queue.recentLog().mapNotNull { line ->
            if (line.trimStart().startsWith("{")) LogFormatter.format(line) else line
        }
        val area = JBTextArea(lines.joinToString("\n").ifEmpty { "아직 실행 로그가 없다" }).apply {
            isEditable = false
            font = JBFont.small()
            caretPosition = document.length
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(area), area)
            .setTitle("실행 로그")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setMinSize(JBUI.size(560, 320))
            .createPopup()
            .showInBestPositionFor(DataManager.getInstance().getDataContext(this))
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    private companion object {
        const val NEW_TERMINAL = "새 터미널"
    }

    /** 조건부 활성 액션을 짧게 만드는 헬퍼 */
    private fun action(
        text: String,
        description: String,
        icon: Icon,
        enabled: () -> Boolean,
        run: () -> Unit,
    ): AnAction =
        object : AnAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = run()
        }

    /** 상태에 따라 아이콘·문구가 바뀌는 토글 */
    private inner class PauseResumeAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val paused = !queue.autoAdvance
            e.presentation.icon = if (paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
            e.presentation.text = if (paused) "자동 진행 재개" else "자동 진행 일시정지"
        }

        override fun actionPerformed(e: AnActionEvent) = togglePause()
    }

    override fun dispose() {
        queue.removeListener(queueListener)
        queue.removeLogListener(logListener)
    }
}
