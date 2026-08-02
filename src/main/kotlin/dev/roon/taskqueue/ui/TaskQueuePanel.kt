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
import com.intellij.util.concurrency.AppExecutorUtil
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
import dev.roon.taskqueue.session.ContextUsage
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionScanner
import dev.roon.taskqueue.terminal.TerminalSessionRegistry
import dev.roon.taskqueue.terminal.TerminalTabs
import dev.roon.taskqueue.terminal.TerminalTabFocuser
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.MouseInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
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
    private val queue = TaskQueueService.getInstance(project)

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

    /**
     * ↻ 를 붙일 항목인지. **좌표 판정과 렌더러가 이 하나를 같이 쓴다** —
     * 어긋나면 안 보이는 버튼이 눌리거나 보이는 버튼이 안 먹는다.
     */
    private val retryable: (TaskEntry) -> Boolean = { task ->
        when (task.status) {
            // 인터럽트해도 훅이 오지 않아 그대로 남는다 — 같은 세션에 다시 보낸다
            TaskStatus.RUNNING -> true
            // 그 방이 비어 있을 때만 — 방 안에서는 순차라 도는 중엔 눌러도 할 일이 없다
            TaskStatus.QUEUED -> queue.runningIn(queue.roomOf(task)) == null
            else -> false
        }
    }

    private val todoColumn = QueueColumn("TODO", "Jot down tasks here", strength, StatusColors.TODO)
    private val doneColumn = QueueColumn("DONE", "Finished tasks", strength, StatusColors.DONE)

    /**
     * 방(터미널 탭)마다 컬럼 하나. 방 사이는 병렬로 도니 나란히 세우는 게 실제 동작과 맞는다.
     * 순서를 지키려고 LinkedHashMap 을 쓴다 — 방이 늘어도 기존 컬럼이 튀지 않게.
     */
    private val roomColumns = linkedMapOf<String, QueueColumn>()

    /** 방 컬럼들이 들어가는 자리. 방이 없으면 빈 컬럼 하나를 세워 자리를 지킨다 */
    private val activeArea = JPanel(GridLayout(1, 0, JBUI.scale(1), 0))

    private val columns: List<QueueColumn>
        get() = listOf(todoColumn) + roomColumns.values + doneColumn

    private fun repaintColumns() = columns.forEach { it.list.repaint() }

    /**
     * 실행 중 카드의 경과 시간을 1초마다 갱신한다.
     * 실행 중 작업이 없으면 멈춘다 — 유휴 상태에서 헛돌 이유가 없다.
     */
    private val ticker = Timer(1_000) { roomColumns.values.forEach { it.list.repaint() } }

    /**
     * 탭별 컨텍스트 점유 툴팁 (`130.3k/1m (13%)  ·  claude-opus-5`).
     * **배경에서 계산해 여기 담는다** — 전사 파일을 EDT 에서 읽으면 UI 가 그만큼 멈춘다.
     *
     * 생성자의 첫 `refresh()` 가 이미 읽으므로 선언이 그보다 위에 있어야 한다.
     */
    private val usageTip = ConcurrentHashMap<String, String>()

    /** 마지막으로 읽은 전사 파일의 크기·수정시각 — 그대로면 다시 읽지 않는다 */
    private val usageStamp = ConcurrentHashMap<String, String>()

    /** 방별 점유 단계 — 헤더 색을 가른다 */
    private val usageLevel = ConcurrentHashMap<String, ContextUsage.Level>()

    /** 실행 중 테두리 맥동 위상 (0..1). 사인으로 부드럽게 오간다 */
    private var pulsePhase = 0f

    private val pulseStrength: () -> Float = { pulsePhase }

    /**
     * 맥동 애니메이션. **실행 중 카드의 셀만** 다시 그린다 — 리스트 전체 repaint 는
     * 초당 여러 번 하면 낭비가 크다. 도는 작업이 없으면 타이머를 멈춘다.
     */
    private val pulser = Timer(PULSE_MS) {
        val step = PULSE_MS.toFloat() / PULSE_PERIOD_MS
        // 0..1 을 왕복하는 사인 — 시작·끝이 완만해 눈에 편하다
        pulseTime = (pulseTime + step) % 1f
        pulsePhase = ((Math.sin(pulseTime * 2 * Math.PI) + 1) / 2).toFloat()
        roomColumns.values.forEach { it.repaintRunning() }
    }

    private var pulseTime = 0f

    /**
     * 터미널 탭이 열리고 닫히는 것은 큐 이벤트가 아니라서 [refresh] 가 불리지 않는다.
     * 주기적으로 훑어 컬럼 구성이 달라졌을 때만 다시 그린다 — 매번 갈아엎으면 선택이 튄다.
     */
    private val tabWatcher = Timer(TAB_POLL_MS) {
        // 이름만 바뀌는 경우는 키가 그대로라 키 비교로는 안 잡힌다 — 표시 이름도 함께 본다
        refreshUsage()
        if (columnSignature() != shownSignature()) refresh()
    }

    /** 지금 있어야 할 컬럼 구성 (키 + 보일 이름) */
    private fun columnSignature(): List<Pair<String, String>> = columnKeys().map { it to titleOf(it) }

    /** 지금 화면에 세워진 컬럼 구성 */
    private fun shownSignature(): List<Pair<String, String>> =
        roomColumns.map { (key, column) -> key to column.title() }

    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        border = JBUI.Borders.empty(3, 6)
    }

    /** 상태줄에 띄울 마지막 진행 메시지 — 전체는 툴바 '로그' 로 본다 */
    private var lastLog = ""

    private var cliInfo = "checking claude…"

    private val queueListener: () -> Unit = { ui { refresh() } }
    private val logListener: (String) -> Unit = { line -> ui { appendLog(line) } }

    /**
     * 에디터에서 넘어온 초안을 입력창에 꽂는다.
     * **이미 친 글자를 덮지 않는다** — 빈 줄 하나 두고 이어붙이고 커서를 끝에 둔다.
     */
    private val draftListener: (String) -> Unit = { draft ->
        ui {
            val current = promptField.text
            promptField.text = if (current.isBlank()) draft else current.trimEnd() + "\n\n" + draft
            promptField.caretPosition = promptField.document.length
            promptField.requestFocusInWindow()
        }
    }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        wirePromptField()
        wireColumns()
        queue.addListener(queueListener)
        queue.addLogListener(logListener)
        queue.addDraftListener(draftListener)

        refreshCliStatus()
        queue.recentLog().lastOrNull()?.let { appendLog(it) }
        refresh()
        tabWatcher.start()
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
            // 실행 중·대기 중도 대상이다 — 인터럽트나 취소로 멈춘 뒤 손댈 방법이 없었다
            add(action("Retry", "Run a task again, or re-send a running one to its terminal",
                AllIcons.Actions.Restart,
                { selected()?.let { it.status.isFinished || retryable(it) } == true }) {
                selected()?.let { retryNow(it) }
            })
            addSeparator()
            add(PauseResumeAction())
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
            // create(...) 는 제거 예정이라 상속으로 쓴다 (클래스 자체는 살아 있다)
            .setRenderer(object : SimpleListCellRenderer<String>() {
                override fun customize(
                    list: JList<out String>,
                    value: String,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value.replace(Regex("\\s+"), " ").trim()
                }
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
        firstComponent = aligned(todoColumn)
        secondComponent = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = activeSection()
            secondComponent = aligned(doneColumn)
        }
    }

    /**
     * 방 컬럼들 위에 구간 이름을 세운다 — 탭 이름만 있으면 TODO·DONE 과 층위가 섞여
     * "여기가 진행 영역" 이 읽히지 않는다.
     */
    private fun activeSection(): JPanel = JPanel(BorderLayout()).apply {
        add(QueueColumn.headerRow("IN PROGRESS", StatusColors.RUNNING), BorderLayout.NORTH)
        add(activeArea, BorderLayout.CENTER)
    }

    /**
     * 진행 영역만 헤더가 두 줄(구간 + 탭 이름)이라 카드 시작 높이가 어긋난다.
     * 같은 구성의 빈 줄을 얹어 맞춘다 — 선은 없고 높이만 같다.
     */
    private fun aligned(column: QueueColumn): JPanel = JPanel(BorderLayout()).apply {
        add(QueueColumn.headerRow("", StatusColors.TODO, separator = false), BorderLayout.NORTH)
        add(column, BorderLayout.CENTER)
    }

    /** 한 컬럼에서 고르면 나머지 선택을 지운다 — 선택은 항상 하나 */
    private fun wireColumns() {
        listOf(todoColumn, doneColumn).forEach { wire(it) }
        TaskDragAndDrop.install(columns, queue::reorderGroup, ::dropTo, fixed = setOf(doneColumn))

        // 완료 정리는 완료 컬럼에만 해당되는 동작이라 그 헤더에 둔다
        doneColumn.setHeaderAction(AllIcons.Actions.GC, "Clear finished and failed tasks") { queue.clearFinished() }
    }

    /** 컬럼 하나에 선택·클릭·단축키를 건다. 방 컬럼은 나중에 생기므로 따로 뽑아둔다 */
    private fun wire(column: QueueColumn) {
        run {
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
                            CardAction.RETRY -> retryNow(task)
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
        registerShortcuts(column)
    }

    /**
     * 카드 목록 단축키. **컴포넌트 범위로 등록한다** — 전역 키맵을 덮지 않으므로
     * F2("다음 오류로 이동") 같은 기본 동작이 다른 곳에서는 그대로 살아 있다.
     */
    private fun registerShortcuts(column: QueueColumn) {
        val list = column.list

        // 맥에서 삭제는 ⌘⌫ 가 자연스럽다 — 없으면 그 조합이 무시돼 "두 번 눌러야 지워진다" 가 된다
        shortcut("DELETE", "BACK_SPACE", "meta BACK_SPACE", "meta DELETE", on = list) {
            column.selected?.let { queue.remove(it.id) }
        }
        shortcut("meta ENTER", on = list) {
            val task = column.selected ?: return@shortcut
            when {
                task.status == TaskStatus.TODO || task.status.isFinished ->
                    chooseTerminal { tab -> queue.promote(task.id, tab) }
                // 이미 탭이 정해진 항목들 — 터미널을 다시 고를 필요가 없다
                retryable(task) -> retryNow(task)
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
    /** 상태에 따라 다른 뜻이다 — 실행 중은 같은 세션에 재전송, 대기 중은 지금 실행 */
    private fun retryNow(task: TaskEntry) = when (task.status) {
        TaskStatus.RUNNING -> queue.resend(task.id)
        TaskStatus.QUEUED -> queue.runNow(task.id)
        else -> queue.retry(task.id)
    }

    private enum class CardAction { EDIT, RUN, RETRY, DELETE }

    /**
     * 클릭 지점이 카드의 버튼 영역인지 판정한다.
     * 리스트 렌더러는 실제 버튼이 클릭을 못 받으므로 좌표로 본다.
     * todo 는 왼쪽부터 ▶ · ✎ · ✕, ↻ 가 붙는 카드는 ↻ · ✕ 다(렌더러와 순서를 맞춰야 한다).
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
            // ↻ 가 붙는 카드는 ✕ 왼쪽 한 칸만 쓴다
            retryable(task) -> if (e.x >= right - 2 * width) CardAction.RETRY to task else null
            !isTodo -> null
            e.x >= right - 2 * width -> CardAction.EDIT to task
            e.x >= right - 3 * width -> CardAction.RUN to task
            else -> null
        }
    }

    /** 다른 컬럼에 떨어뜨렸을 때의 상태 이동 */
    private fun dropTo(task: TaskEntry, target: QueueColumn) {
        val key = roomColumns.entries.firstOrNull { it.value === target }?.key
        when {
            target === todoColumn && task.status == TaskStatus.QUEUED -> queue.demote(task.id)
            // 실행 중 카드는 방을 옮기지 않는다 — 이미 그 탭의 claude 가 물고 있다
            task.status == TaskStatus.RUNNING -> Unit
            key == null -> Unit // 완료 컬럼 등
            else -> {
                val tab = tabOf(key)
                when {
                    tab == null -> appendLog("· that terminal can't be used — pick another one")
                    task.status == TaskStatus.TODO -> queue.promote(task.id, tab)
                    // 대기 항목을 다른 방으로 — 그 탭에서 돌게 된다
                    task.status == TaskStatus.QUEUED -> queue.moveToRoom(task.id, tab)
                }
            }
        }
    }

    /**
     * 컬럼 키 → **실행에 쓸 수 있는** 탭 이름.
     *
     * 컬럼 키는 등록된 탭이면 우리 라벨이지만, 아직 안 쓴 탭이면 그냥 현재 제목이다.
     * 후자를 그대로 실행에 넘기면 런처가 레지스트리에서 못 찾아 "That terminal tab is gone"
     * 으로 실패한다 — 팔레트가 하는 것처럼 **여기서 등록**한 뒤 그 라벨을 쓴다.
     *
     * @return 탭 이름, '새 대화' 면 빈 문자열, 다룰 수 없는 탭이면 null
     */
    private fun tabOf(key: String): String? {
        if (key == NEW_COLUMN || key == PLACEHOLDER) return ""
        if (TerminalSessionRegistry.getInstance().find(key) != null) return key
        val content = TerminalTabs.contents(project).firstOrNull {
            it.displayName?.takeIf { name -> name.isNotBlank() } == key
        } ?: return null
        return TerminalTabs.pinContent(project, content)
    }

    /**
     * 컬럼에 보일 이름. **키(정체성)와 표시 이름을 나눈다** —
     * 키는 작업이 물고 있는 탭 라벨이라 바뀌면 안 되고, 표시 이름은 사용자가 탭을
     * 바꾸면 따라가야 한다. 사용자가 안 바꿨으면 키를 그대로 쓴다.
     */
    private fun titleOf(key: String): String = when (key) {
        PLACEHOLDER -> "IN PROGRESS"
        NEW_COLUMN -> "NEW CONVERSATION"
        else -> (userTitleOf(key) ?: key).uppercase()
    }

    /**
     * 점유율을 다시 계산한다.
     *
     * 자동 compact 임박 여부가 "작업을 더 넣을지" 판단에 직접 쓰인다. 임계는
     * [ContextUsage] 의 값을 그대로 쓰고, HEAVY(80%+) 면 눈에 걸리게 표시를 더한다.
     *
     * **세션 ID 는 EDT 에서 모아 배경으로 넘긴다** — 레지스트리·위젯 상태를 배경 스레드에서
     * 만지지 않기 위해서다. 파일 읽기와 파싱만 배경에서 한다.
     *
     * 전사 파일은 지연되어 쓰이므로 값이 실시간보다 조금 늦을 수 있다. 세션 ID 를 아직
     * 모르는 탭(사용자가 직접 열어 바인딩 전)은 표시하지 않는다.
     */
    private fun refreshUsage() {
        val registry = runCatching { TerminalSessionRegistry.getInstance() }.getOrNull() ?: return
        val targets = roomColumns.keys.mapNotNull { key ->
            registry.find(key)?.sessionId?.let { key to it }
        }
        val dir = cwd()
        AppExecutorUtil.getAppExecutorService().execute {
            var changed = false
            for ((key, sessionId) in targets) {
                val file = SessionPaths.sessionFile(dir, sessionId)
                // 파일이 그대로면 읽지 않는다 — 2초 폴링이라 대화가 멈춘 동안 I/O 가 0 이 된다
                val stamp = "${file.length()}:${file.lastModified()}"
                if (usageStamp.put(key, stamp) == stamp) continue

                val (next, level) = runCatching { usageOf(file) }.getOrDefault("" to ContextUsage.Level.OK)
                usageLevel[key] = level
                if (usageTip.put(key, next) != next) changed = true
            }
            if (changed) ui { refresh() }
        }
    }

    /**
     * @return 헤더 툴팁 문구 + 그 점유 단계.
     *   숫자는 헤더에 쓰지 않는다 — 탭 이름 옆에 붙이면 좁은 컬럼에서 이름이 잘린다.
     *   한눈에 필요한 '차오름' 은 색이 알려주고, 정확한 값은 올려보면 나온다.
     */
    private fun usageOf(file: File): Pair<String, ContextUsage.Level> {
        val (tokens, model) = SessionScanner.lastContext(file)
        if (tokens <= 0) return "" to ContextUsage.Level.OK
        val pct = ContextUsage.percent(tokens, ContextUsage.contextLimit(model, tokens))
        val tip = ContextUsage.label(tokens, model) + model.ifEmpty { null }?.let { "  ·  $it" }.orEmpty()
        return tip to ContextUsage.level(pct)
    }

    /** 그 탭에 사용자가 붙인 이름 (앱이 바꾼 제목은 제외) */
    private fun userTitleOf(key: String): String? = runCatching {
        TerminalSessionRegistry.getInstance().find(key)?.handle?.userTitle()
    }.getOrNull()

    /**
     * 카드가 들어갈 컬럼. **탭 이름 하나로만 가른다** — 서비스의 방은 묶음까지 구분하지만
     * 화면에서는 아직 탭이 없는 것들을 '새 대화' 한 칸에 모은다.
     */
    private fun columnKeyOf(task: TaskEntry): String = task.terminalTab.ifEmpty { NEW_COLUMN }

    /**
     * 컬럼 헤더 색. 탭마다 다른 색이라 어느 대화방인지 눈으로 구분된다 —
     * 전부 진행 초록이면 컬럼끼리 안 갈린다. 탭이 아직 없는 칸은 중립 회색.
     */
    /**
     * 헤더 색. 평소에는 방 색이지만 **컨텍스트가 차오르면 경고색이 이긴다** —
     * 그 시점에는 어느 방인지보다 곧 compact 된다는 사실이 중요하다.
     */
    private fun accentOf(key: String): com.intellij.ui.JBColor = when {
        usageLevel[key] == ContextUsage.Level.HEAVY -> StatusColors.CONTEXT_HEAVY
        usageLevel[key] == ContextUsage.Level.WARN -> StatusColors.CONTEXT_WARN
        key == NEW_COLUMN || key == PLACEHOLDER -> StatusColors.TODO
        else -> RoomColors.of(key)
    }

    /**
     * 세울 컬럼들.
     *
     * **작업이 있는 방만 세우면 컬럼이 생겼다 사라졌다 한다.** 그래서 열려 있는 탭을 기준으로
     * 삼는다 — 탭을 열고 닫을 때만 바뀌므로 화면이 안정적이고, 비어 있는 탭 컬럼이
     * 드롭 대상이 되어 "저 탭에서 돌려" 를 드래그로 표현할 수 있다.
     */
    private fun columnKeys(): List<String> {
        val registry = runCatching { TerminalSessionRegistry.getInstance() }.getOrNull()
        // 이 프로젝트의 터미널 탭 전부. 우리가 등록한 탭은 그 라벨을 쓴다 —
        // claude 가 실행되며 탭 제목을 바꾸므로(예: "✳ Claude Code") 제목만 믿으면 컬럼이 갈라진다
        val open = runCatching {
            TerminalTabs.contents(project).mapNotNull { content ->
                registry?.findByContent(content)?.label
                    ?: content.displayName?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        // 탭이 닫혔는데 아직 그 탭을 물고 있는 작업이 남아 있을 수 있다 — 그 카드가 사라지면 안 된다
        val withTasks = queue.tasks.filter { it.status.isActive }.map(::columnKeyOf)

        val keys = (open + withTasks).distinct()
        // '새 대화' 칸은 갈 곳 없는 작업이 있을 때만 — 늘 띄우면 빈 칸이 거슬린다
        val needsNew = NEW_COLUMN in withTasks
        val result = keys.filter { it != NEW_COLUMN } + if (needsNew) listOf(NEW_COLUMN) else emptyList()
        return result.ifEmpty { listOf(PLACEHOLDER) }
    }

    /**
     * 방 목록이 바뀌었을 때만 컬럼을 다시 세운다 — 매번 갈아엎으면 선택·스크롤이 튄다.
     * 새로 만든 컬럼에는 기존 컬럼과 같은 배선(선택·클릭·단축키·드래그)을 걸어준다.
     */
    private fun syncRoomColumns(wanted: List<String>) {
        if (wanted == roomColumns.keys.toList()) return

        val kept = roomColumns.filterKeys { it in wanted }
        roomColumns.clear()
        wanted.forEach { room ->
            roomColumns[room] = kept[room] ?: QueueColumn(
                titleOf(room), "Runs in order once promoted", strength, accentOf(room), retryable,
                pulseStrength,
            ).also { wire(it) }
        }

        activeArea.removeAll()
        roomColumns.values.forEach { activeArea.add(it) }
        activeArea.revalidate()
        activeArea.repaint()

        // 목표 컬럼 목록이 바뀌었으므로 드래그 핸들러를 다시 건다 (교체라 중복되지 않는다)
        TaskDragAndDrop.install(columns, queue::reorderGroup, ::dropTo, fixed = setOf(doneColumn))
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

    /** 링크를 풀어 둔다 — claude 가 전사를 남기는 경로와 같아야 찾을 수 있다 */
    private fun cwd(): String =
        SessionPaths.canonical(project.basePath ?: File(System.getProperty("user.home")).absolutePath)

    /**
     * 실행할 터미널을 팔레트로 고른다. 열린 탭이 없으면 묻지 않고 새 탭으로 진행.
     * @param onChosen 선택된 탭 이름 ("" = 새 탭)
     */
    private fun chooseTerminal(anchor: RelativePoint? = null, onChosen: (String) -> Unit) {
        val tabs = TerminalTabs.list(project)
        if (tabs.isEmpty()) {
            // 탭이 있는데 하나도 못 쓰는 상황과 아예 없는 상황은 다르다 —
            // 조용히 새 탭을 열면 왜 내 터미널이 무시됐는지 알 길이 없다
            if (TerminalTabs.hasUnusableTabs(project)) {
                Messages.showWarningDialog(
                    project,
                    "Your open terminal tabs can't be used with the Reworked terminal engine.\n\n" +
                        "Switch it in Settings → Tools → Terminal → Terminal engine → Classic,\n" +
                        "then restart the IDE. A new tab will be opened for now.",
                    "Task Queue",
                )
            }
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
        syncRoomColumns(columnKeys())
        todoColumn.setTasks(all.filter { it.status == TaskStatus.TODO })
        roomColumns.forEach { (key, column) ->
            // 탭 이름이 바뀌었을 수 있다 — 목록보다 먼저 제목을 맞춘다
            column.setTitle(titleOf(key))
            // 점유가 오르내리면 헤더 색이 따라가고, 정확한 값은 툴팁으로 준다
            column.setAccent(accentOf(key))
            column.setHeaderTooltip(usageTip[key]?.ifEmpty { null })
            column.setTasks(all.filter { it.status.isActive && columnKeyOf(it) == key })
        }
        // 끝난 것은 최근이 위 — 방금 뭐가 끝났는지 보려고 보는 컬럼이다.
        // (드래그 대상이 아니라서 순서를 바꿔도 전역 순서에 영향이 없다)
        doneColumn.setTasks(
            all.filter { it.status.isFinished }
                .sortedByDescending { it.finishedAt ?: it.createdAt }
        )
        doneColumn.setHeaderActionEnabled(all.any { it.status.isFinished })

        // 상태가 바뀐 카드에 잔상을 걸고, 새 컬럼에서 보이게 스크롤한다
        val moved = highlighter.onTasksChanged(all.map { it.id to it.status })
        moved.forEach { id -> columns.forEach { it.scrollTo(id) } }

        // 실행 중일 때만 초 단위 갱신
        if (queue.runningTask() != null) {
            if (!ticker.isRunning) ticker.start()
            if (!pulser.isRunning) pulser.start()
        } else {
            if (ticker.isRunning) ticker.stop()
            if (pulser.isRunning) pulser.stop()
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

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    private companion object {
        /** 아직 탭이 정해지지 않은 작업들이 모이는 컬럼 */
        const val NEW_COLUMN = "\u0000new"

        /** 보여줄 탭도 작업도 없을 때 자리를 지키는 빈 컬럼 */
        const val PLACEHOLDER = "\u0000none"

        /** 터미널 탭 열림·닫힘 감시 주기 */
        const val TAB_POLL_MS = 2_000

        /** 맥동 갱신 간격. 8fps 면 부드럽게 보이고 비용은 거의 없다 */
        const val PULSE_MS = 120

        /** 한 번 숨쉬는 데 걸리는 시간 */
        const val PULSE_PERIOD_MS = 2_000f

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
        queue.removeDraftListener(draftListener)
        highlighter.stop()
        ticker.stop()
        pulser.stop()
        tabWatcher.stop()
    }
}
