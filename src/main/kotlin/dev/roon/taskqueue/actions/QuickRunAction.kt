package dev.roon.taskqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import dev.roon.taskqueue.ui.QuickRunPopup

/**
 * IDE 어디서든 작업 하나를 만들어 특정 터미널로 던진다.
 *
 * **툴윈도를 열지 않아도 된다**는 것이 존재 이유다 — 지금은 뭘 적어두려 해도
 * 마우스로 툴윈도부터 열어야 하고, 특정 탭에서 돌리려면 카드를 드래그해야 한다.
 */
class QuickRunAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        QuickRunPopup(project).show()
    }
}
