package dev.roon.taskqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.roon.taskqueue.queue.TaskQueueService

/**
 * 에디터의 선택 영역/파일을 **작업 입력창에 초안으로 꽂는다.**
 * 프롬프트에 `파일:라인` 을 넣어 모델이 위치를 알고 시작하게 한다.
 *
 * 예전에는 여기서 모달로 지시를 물어 곧바로 TODO 를 만들었다. 그 대화상자는 한 줄 입력이라
 * 여러 줄을 받는 패널 입력창보다 좁았고, 컨텍스트를 고칠 수도 없었다 —
 * 입력은 패널 한 곳으로 모은다.
 */
class EnqueueSelectionAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val context = buildContext(project.basePath, file, editor)

        // 툴윈도를 먼저 연다 — 닫혀 있으면 패널이 아직 없어 초안을 받을 곳이 없다.
        // 콜백에서 보내야 패널이 구독을 마친 뒤에 도착한다
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)
        if (toolWindow == null) {
            TaskQueueService.getInstance(project).proposeDraft(context)
            return
        }
        toolWindow.activate { TaskQueueService.getInstance(project).proposeDraft(context) }
    }

    /** `대상: src/Foo.kt:12-30` + 선택 텍스트 발췌 */
    private fun buildContext(basePath: String?, file: VirtualFile, editor: Editor?): String {
        val path = basePath?.let { base ->
            file.path.removePrefix(base.trimEnd('/') + "/")
        } ?: file.path

        val selection = editor?.selectionModel?.takeIf { it.hasSelection() } ?: return "Target: $path"
        val doc = editor.document
        val startLine = doc.getLineNumber(selection.selectionStart) + 1
        val endLine = doc.getLineNumber(selection.selectionEnd) + 1
        val range = if (startLine == endLine) "$startLine" else "$startLine-$endLine"

        val snippet = selection.selectedText.orEmpty().let {
            if (it.length > SNIPPET_MAX) it.take(SNIPPET_MAX) + "\n…(truncated)" else it
        }
        return "Target: $path:$range\n```\n$snippet\n```"
    }

    companion object {
        private const val TOOL_WINDOW = "Claude Task Queue"
        private const val SNIPPET_MAX = 2000
    }
}
