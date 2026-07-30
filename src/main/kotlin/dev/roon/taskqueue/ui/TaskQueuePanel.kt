package dev.roon.taskqueue.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import java.awt.MouseInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 자동작업 큐 보드 — todo / 진행 / 완료 3컬럼.
 * 추가는 todo 로만 들어가고, 진행으로 옮길 때(드래그 또는 ▶) 실행된다.
 * claude 출력은 터미널 탭에 그대로 있으므로 여기서 다시 보여주지 않는다.
 */
class TaskQueuePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val cli = ClaudeCli.getInstance()
    private val queue = TaskQueueService.getInstance()

    /**
     * 작업 입력창. 여러 줄을 받는다 — 실행 엔진은 여러 줄을 지원하는데
     * 한 줄 입력창이 그보다 좁은 제약이었다.
     *
     * Enter 로 추가, Shift+Enter 로 줄바꿈 (채팅앱 관례). 내용에 따라 높이가 늘어난다.
     */
    private val promptField = JBTextArea(INPUT_MIN_ROWS, 0).apply {
        emptyText.text = "Type a task and press Enter — added to TODO (Shift+Enter for a new line)"
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(3, 5)
    }



    /**
     * 상태가 바뀐 카드의 잔상 — 카드가 소리 없이 순간이동하는 걸 눈으로 따라가게.
     * 타입을 명시한다: 콜백이 `columns` 를 참조해 추론이 순환에 걸린다.
     */
    private val highlighter: CardHighlighter = CardHighlighter { repaintColumns() }

    private val strength: (TaskEntry) -> Float = { task -> highlighter.strength(task.id) }

    private val todoColumn = QueueColumn("TODO", "Jot down tasks here", strength, StatusColors.TODO)
    private val activeColumn =
        QueueColumn("IN PROGRESS", "Runs in order once promoted", strength, StatusColors.RUNNING)
    private val doneColumn = QueueColumn("DONE", "Finished tasks", strength, StatusColors.DONE)
    private val columns: List<QueueColumn> = listOf(todoColumn, activeColumn, doneColumn)

    private fun repaintColumns() = columns.forEach { it.list.repaint() }

    /**
     * 실행 중 카드의 경과 시간을 1초마다 갱신한다.
     * 실행 중 작업이 없으면 멈춘다 — 유휴 상태에서 헛돌 이유가 없다.
     */
    private val ticker = Timer(1_000) { activeColumn.list.repaint() }

    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        border = JBUI.Borders.empty(3, 6)
    }

    /** 상태줄에 띄울 마지막 진행 메시지 — 전체는 툴바 '로그' 로 본다 */
    private var lastLog = ""

    private var cliInfo = "checking claude…"

    private val queueListener: () -> Unit = { ui { refresh() } }
    private val logListener: (String) -> Unit = { line -> ui { appendLog(line) } }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        wirePromptField()
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
            add(action("Run all TODO", "Promote every TODO task", AllIcons.Actions.RunAll,
                { queue.todos().isNotEmpty() }) {
                chooseTerminal { tab -> queue.runAllTodos(tab) }
            })
            addSeparator()
            add(action("Stop queue", "Stop starting new tasks and move waiting ones back to TODO",
                AllIcons.Actions.Suspend,
                { queue.runningTask() != null || queue.queued().isNotEmpty() }) {
                queue.stopQueue()
            })
            add(action("Retry", "Run a failed or canceled task again", AllIcons.Actions.Restart,
                { selected()?.status?.isFinished == true }) {
                selected()?.let { queue.retry(it.id) }
            })
            addSeparator()
            add(PauseResumeAction())
            addSeparator()
            add(action("Log", "Plugin activity log (claude output lives in the terminal tab)",
                AllIcons.Debugger.Console, { true }) { showLog() })
        }

        val toolbar: ActionToolbar = ActionManager.getInstance()
            .createActionToolbar("TaskQueue.Toolbar", group, true)
        toolbar.targetComponent = this

        val inputRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JBScrollPane(promptField), BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(inputRow, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(2, 4)
        }
    }

    /**
     * Enter = 추가, Shift+Enter = 줄바꿈.
     * 텍스트 영역은 기본적으로 Enter 가 줄바꿈이라 그 바인딩을 갈아끼운다.
     */
    private fun wirePromptField() {
        val input = promptField.inputMap
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "taskqueue-add")
        // 빈 입력창에서만 ↑ 를 가로챈다 — 내용이 있으면 커서 이동이어야 한다
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "taskqueue-history")
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break")
        promptField.actionMap.put("taskqueue-add", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = addTodo()
        })
        promptField.actionMap.put("taskqueue-history", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (promptField.text.isEmpty()) showHistory() else moveCaretUp()
            }
        })

        // 내용이 길어지면 높이를 늘린다 — 긴 프롬프트를 추가 전에 눈으로 확인할 수 있게
        promptField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = growToFit()
            override fun removeUpdate(e: DocumentEvent) = growToFit()
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
    }

    /**
     * 최근 프롬프트를 팔레트로 보여준다. 고르면 **입력창에 채우기만** 한다 —
     * 대개 조금 고쳐서 쓰므로 추가는 사람이 Enter 로 결정한다.
     */
    private fun showHistory() {
        val items = queue.history()
        if (items.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Recent prompts")
            .setVisibleRowCount(HISTORY_ROWS)
            .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                label.text = value.replace(Regex("\\s+"), " ").trim()
            })
            .setItemChosenCallback { chosen ->
                promptField.text = chosen
                promptField.caretPosition = chosen.length
                promptField.requestFocusInWindow()
            }
            .createPopup()
            .showUnderneathOf(promptField)
    }

    /** 가로챈 ↑ 의 원래 동작 — 내용이 있을 때는 커서를 위로 */
    private fun moveCaretUp() {
        val line = promptField.getLineOfOffset(promptField.caretPosition)
        if (line <= 0) return
        val column = promptField.caretPosition - promptField.getLineStartOffset(line)
        val target = promptField.getLineStartOffset(line - 1) + column
        promptField.caretPosition = target.coerceAtMost(promptField.getLineEndOffset(line - 1))
    }

    private fun growToFit() {
        val lines = promptField.text.count { it == '\n' } + 1
        val rows = lines.coerceIn(INPUT_MIN_ROWS, INPUT_MAX_ROWS)
        if (rows != promptField.rows) {
            promptField.rows = rows
            revalidate()
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
                val task = column.selected ?: return@addListSelectionListener
                columns.filter { it !== column }.forEach { it.clearSelection() }
                // 실행 중 항목을 고르면 그 터미널 탭을 앞으로 — 포커스는 뺏지 않는다
                if (task.status == TaskStatus.RUNNING && task.terminalTab.isNotEmpty()) {
                    focusTerminal(task, moveFocus = false)
                }
            }
            column.list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 카드 오른쪽 버튼 영역 — 선택·더블클릭보다 먼저 처리한다
                    if (e.clickCount == 1) {
                        val hit = cardActionAt(column, e) ?: return
                        val (what, task) = hit
                        when (what) {
                            CardAction.DELETE -> queue.remove(task.id)
                            CardAction.RUN -> chooseTerminal(RelativePoint(e)) { tab ->
                                queue.promote(task.id, tab)
                            }
                            CardAction.EDIT -> editPrompt(task)
                        }
                        return
                    }
                    if (e.clickCount != 2) return
                    val task = column.selected ?: return
                    when (task.status) {
                        // 실행으로 올리는 건 드래그가 하니, 더블클릭은 수정에 쓴다
                        TaskStatus.TODO -> editPrompt(task)
                        // 대기줄에 올라간 뒤엔 곧 실행되므로 고치지 않는다 (TODO 로 내려서 고쳐야 한다)
                        TaskStatus.QUEUED -> Unit
                        // 실행 중이면 그 claude 터미널로 보내준다 — 개입하려면 그게 필요하다
                        TaskStatus.RUNNING -> focusTerminal(task)
                        else -> Unit
                    }
                }
            })
        }

        columns.forEach { registerShortcuts(it) }
        TaskDragAndDrop.install(columns, queue::reorderGroup, ::dropTo, fixed = setOf(doneColumn))

        // 완료 정리는 완료 컬럼에만 해당되는 동작이라 그 헤더에 둔다
        doneColumn.setHeaderAction(AllIcons.Actions.GC, "Clear finished and failed tasks") { queue.clearFinished() }
    }

    /**
     * 카드 목록 단축키. **컴포넌트 범위로 등록한다** — 전역 키맵을 덮지 않으므로
     * F2("다음 오류로 이동") 같은 기본 동작이 다른 곳에서는 그대로 살아 있다.
     */
    private fun registerShortcuts(column: QueueColumn) {
        val list = column.list

        shortcut("DELETE", "BACK_SPACE", on = list) {
            column.selected?.let { queue.remove(it.id) }
        }
        shortcut("meta ENTER", on = list) {
            val task = column.selected ?: return@shortcut
            if (task.status == TaskStatus.TODO || task.status.isFinished) {
                chooseTerminal { tab -> queue.promote(task.id, tab) }
            }
        }
        shortcut("F2", on = list) {
            column.selected?.takeIf { it.status == TaskStatus.TODO }?.let { editPrompt(it) }
        }
    }

    private fun shortcut(vararg keys: String, on: JComponent, run: () -> Unit) {
        DumbAwareAction.create { run() }
            .registerCustomShortcutSet(CustomShortcutSet.fromString(*keys), on)
    }

    /** 카드 오른쪽 버튼 영역 클릭 결과 */
    private enum class CardAction { EDIT, RUN, DELETE }

    /**
     * 클릭 지점이 카드의 버튼 영역인지 판정한다.
     * 리스트 렌더러는 실제 버튼이 클릭을 못 받으므로 좌표로 본다.
     * 왼쪽부터 ▶ · ✎ · ✕ 순이고, ▶/✎ 는 todo 에만 있다(렌더러와 순서를 맞춰야 한다).
     */
    private fun cardActionAt(column: QueueColumn, e: MouseEvent): Pair<CardAction, TaskEntry>? {
        val index = column.list.locationToIndex(e.point).takeIf { it >= 0 } ?: return null
        val bounds = column.list.getCellBounds(index, index) ?: return null
        if (!bounds.contains(e.point)) return null
        // 아이콘은 마우스 올린·선택된 카드에만 보인다 — 안 보이면 클릭도 없다
        if (!column.actionsVisible(index)) return null

        val task = column.list.model.getElementAt(index)
        val width = JBUI.scale(TaskCardRenderer.ACTION_WIDTH)
        val right = bounds.x + bounds.width
        val isTodo = task.status == TaskStatus.TODO

        return when {
            e.x >= right - width -> CardAction.DELETE to task
            !isTodo -> null
            e.x >= right - 2 * width -> CardAction.EDIT to task
            e.x >= right - 3 * width -> CardAction.RUN to task
            else -> null
        }
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


    /**
     * TODO 항목의 프롬프트를 고친다.
     * 한 줄 입력창이라 엔터가 곧 확인이다 — 전송할 때도 한 줄로 합쳐지므로 실제 동작과 맞는다.
     */
    private fun editPrompt(task: TaskEntry) {
        val edited = Messages.showInputDialog(
            project, "Prompt", "Edit Task", null, task.prompt, null,
        ) ?: return
        queue.updatePrompt(task.id, edited)
    }

    /** @param moveFocus 더블클릭·버튼은 포커스까지, 단순 선택은 탭만 앞으로 */
    private fun focusTerminal(task: TaskEntry, moveFocus: Boolean = true) {
        if (!TerminalTabFocuser.focus(project, task.terminalTab, moveFocus)) {
            appendLog("· terminal tab not found: ${task.terminalTab.ifEmpty { "(none)" }}")
        }
    }

    private fun cwd(): String =
        project.basePath ?: File(System.getProperty("user.home")).absolutePath

    /**
     * 실행할 터미널을 팔레트로 고른다. 열린 탭이 없으면 묻지 않고 새 탭으로 진행.
     * @param onChosen 선택된 탭 이름 ("" = 새 탭)
     */
    private fun chooseTerminal(anchor: RelativePoint? = null, onChosen: (String) -> Unit) {
        val tabs = TerminalTabs.list(project)
        if (tabs.isEmpty()) {
            onChosen("")
            return
        }

        val items = listOf(NEW_TERMINAL) + tabs.map { it.display }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Run in terminal")
            .setMovable(false)
            .setResizable(false)
            .setItemChosenCallback { chosen ->
                // 고른 탭을 레지스트리에 고정한다 — 제목이 바뀌어도 위젯 참조로 찾는다
                val tab = tabs.firstOrNull { it.display == chosen }?.let(TerminalTabs::pin) ?: ""
                onChosen(tab)
            }
            .createPopup()

        // 누른 자리에 띄운다 — showInBestPositionFor 는 패널 기준으로 잡아 엉뚱한 데 뜬다
        val at = anchor ?: cursorPoint()
        if (at != null) popup.show(at) else popup.showInFocusCenter()
    }

    /** 마우스가 있는 곳 — 드래그·툴바 조작 모두 커서 근처가 자연스럽다 */
    private fun cursorPoint(): RelativePoint? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { RelativePoint.fromScreen(it) }
    }.getOrNull()

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
                    cliInfo = "claude CLI not found — install it and restart the IDE"
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
        doneColumn.setHeaderActionEnabled(all.any { it.status.isFinished })

        // 상태가 바뀐 카드에 잔상을 걸고, 새 컬럼에서 보이게 스크롤한다
        val moved = highlighter.onTasksChanged(all.map { it.id to it.status })
        moved.forEach { id -> columns.forEach { it.scrollTo(id) } }

        // 실행 중일 때만 초 단위 갱신
        if (queue.runningTask() != null) {
            if (!ticker.isRunning) ticker.start()
        } else if (ticker.isRunning) {
            ticker.stop()
        }

        statusLabel.text = buildString {
            append(if (queue.autoAdvance) "Auto-advance ON" else "Paused")
            queue.runningTask()?.let { append("  ·  running: ${it.shortLabel()}") }
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
        val area = JBTextArea(lines.joinToString("\n").ifEmpty { "No activity yet" }).apply {
            isEditable = false
            font = JBFont.small()
            caretPosition = document.length
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(area), area)
            .setTitle("Activity Log")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setMinSize(JBUI.size(560, 320))
            .createPopup()
            .let { popup ->
                val at = cursorPoint()
                if (at != null) popup.show(at) else popup.showInFocusCenter()
            }
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    private companion object {
        const val NEW_TERMINAL = "New terminal"

        /** 히스토리 팔레트에 한 번에 보이는 줄 수 (나머지는 스크롤) */
        const val HISTORY_ROWS = 5

        /** 입력창 높이 범위 — 기본 1줄, 줄바꿈하면 6줄까지 늘어난다 */
        const val INPUT_MIN_ROWS = 1
        const val INPUT_MAX_ROWS = 6
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
            e.presentation.text = if (paused) "Resume auto-advance" else "Pause auto-advance"
        }

        override fun actionPerformed(e: AnActionEvent) = togglePause()
    }

    override fun dispose() {
        queue.removeListener(queueListener)
        queue.removeLogListener(logListener)
        highlighter.stop()
        ticker.stop()
    }
}
