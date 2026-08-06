package dev.roon.taskqueue.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.roon.taskqueue.queue.TaskQueueService
import dev.roon.taskqueue.session.SessionPaths
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * IDE 어디서든 **한 번에** 작업을 만들어 특정 터미널로 보낸다.
 *
 * 툴윈도를 열 필요가 없다는 것이 요점이다 — 지금은 무엇을 적어두려 해도
 * 마우스로 툴윈도를 먼저 열어야 한다.
 *
 * 실행 경로는 새로 만들지 않는다. 카드를 탭 컬럼으로 드래그했을 때와 **같은 두 줄**을 탄다
 * (`addTodo(terminalTab)` → `promote`). 그래서 답변 중인 탭을 고르면 기존처럼 줄을 선다.
 */
class QuickRunPopup(private val project: Project) {

    private val queue get() = TaskQueueService.getInstance(project)

    private val field = JBTextField().apply {
        columns = 48
        emptyText.text = "무엇을 시킬까요?  (↑ 최근 프롬프트)"
    }

    /** 최근 프롬프트 훑기 — 입력창의 ↑ 와 같은 감각 */
    private var historyIndex = -1

    private var popup: JBPopup? = null

    fun show() {
        installHistoryKeys()
        field.addActionListener { next() }

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(field, BorderLayout.CENTER)
            add(JBLabel("Enter 로 다음 → 어디서 실행할지 고릅니다").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(POPUP_WIDTH), preferredSize.height)
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, field)
            .setTitle(TITLE)
            .setRequestFocus(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        val at = TerminalPalette.cursorPoint()
        if (at != null) popup?.show(at) else popup?.showInFocusCenter()
    }

    /** 1단계 확정 → 2단계(어디서 실행할지) */
    private fun next() {
        val prompt = field.text.trim()
        if (prompt.isEmpty()) return
        popup?.cancel()

        TerminalPalette.choose(
            project = project,
            title = "어디서 실행할까요",
            preselectLabel = lastTarget(),
            extraItems = listOf(TO_TODO),
            onExtra = {
                // 지금 안 돌린다 — TODO 에만 쌓는다. promote 하지 않는 것이 차이의 전부다
                queue.addTodo(prompt, cwd())
            },
            onChosen = { label ->
                rememberTarget(label)
                val task = queue.addTodo(prompt, cwd(), terminalTab = label)
                queue.promote(task.id, label)
            },
        )
    }

    /** 입력창(`TaskQueuePanel`)의 ↑ 와 같은 동작 — 최근 프롬프트를 거슬러 올라간다 */
    private fun installHistoryKeys() {
        field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "quickrun-history-prev")
        field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "quickrun-history-next")
        field.actionMap.put("quickrun-history-prev", step(+1))
        field.actionMap.put("quickrun-history-next", step(-1))
    }

    private fun step(delta: Int) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) {
            val history = queue.history()
            if (history.isEmpty()) return
            historyIndex = (historyIndex + delta).coerceIn(-1, history.lastIndex)
            field.text = if (historyIndex < 0) "" else history[historyIndex]
            field.caretPosition = field.text.length
        }
    }

    private fun cwd(): String =
        SessionPaths.canonical(project.basePath ?: File(System.getProperty("user.home")).absolutePath)

    /**
     * 마지막에 고른 탭을 다음 번에 미리 선택한다 — 같은 탭에 연달아 보내는 것이 흔하다.
     * 탭이 사라졌으면 [TerminalPalette] 가 알아서 무시한다.
     */
    private fun lastTarget(): String? =
        PropertiesComponent.getInstance(project).getValue(LAST_TARGET_KEY)

    private fun rememberTarget(label: String) {
        // 새 탭("")은 기억하지 않는다 — 매번 새 탭이 미리 선택되면 오히려 방해가 된다
        if (label.isEmpty()) return
        PropertiesComponent.getInstance(project).setValue(LAST_TARGET_KEY, label)
    }

    private companion object {
        const val TITLE = "Claude Task Queue"
        const val TO_TODO = "TODO 에 쌓기 (지금 실행 안 함)"
        const val LAST_TARGET_KEY = "taskqueue.quickrun.lastTarget"
        const val POPUP_WIDTH = 420
    }
}
