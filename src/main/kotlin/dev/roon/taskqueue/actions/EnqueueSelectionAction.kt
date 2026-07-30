package dev.roon.taskqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.roon.taskqueue.queue.TaskQueueService

/**
 * 에디터에서 선택 영역/파일을 작업으로 큐잉한다.
 * 프롬프트에 `파일:라인` 을 넣어 모델이 위치를 알고 시작하게 한다.
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

        val instruction = Messages.showInputDialog(
            project,
            "$context\n\nWhat should I do?",
            "Add to Task Queue",
            null,
            "",
            null,
        )?.trim().orEmpty()
        if (instruction.isEmpty()) return

        val cwd = project.basePath ?: return
        // todo 로만 넣는다 — 실행은 큐 보드에서 ▶ 로 (바로 도는 건 의도 밖 실행)
        TaskQueueService.getInstance(project).addTodo("$context\n\n$instruction", cwd)

        ToolWindowManager.getInstance(project).getToolWindow("Task Queue")?.activate(null)
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
        private const val SNIPPET_MAX = 2000
    }
}
