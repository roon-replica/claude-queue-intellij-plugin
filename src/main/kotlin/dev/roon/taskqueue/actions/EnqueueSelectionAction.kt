package dev.roon.taskqueue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
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
            e.project != null && (localFile(e) != null || selectedText(e) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = localFile(e)
        val editor = anyEditor(e)

        // 로컬 파일이 없으면 터미널 등 파일 밖의 선택이다 — 위치 표기 없이 텍스트만 초안으로
        val context = if (file != null) buildContext(project.basePath, file, editor)
        else selectedText(e)?.let(::truncate) ?: return

        // 툴윈도를 먼저 연다 — 닫혀 있으면 패널이 아직 없어 초안을 받을 곳이 없다.
        // 콜백에서 보내야 패널이 구독을 마친 뒤에 도착한다
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)
        if (toolWindow == null) {
            TaskQueueService.getInstance(project).proposeDraft(context)
            return
        }
        toolWindow.activate { TaskQueueService.getInstance(project).proposeDraft(context) }
    }

    /**
     * 실제 파일일 때만 위치를 붙인다.
     * 터미널 출력도 에디터라 `VIRTUAL_FILE` 이 잡히지만 로컬 파일이 아니다 —
     * 거르지 않으면 `Target: /terminal/...` 같은 엉뚱한 경로가 초안에 박힌다.
     */
    private fun localFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }

    /**
     * 터미널은 `CommonDataKeys.EDITOR` 를 싣지 않는다(실측: 우클릭 시 null).
     * 신 터미널은 자기 `DataKey("TERMINAL_EDITOR")` 로 에디터를 넘긴다 —
     * 플랫폼의 `TerminalActionUtil.EDITOR_KEY` 와 **같은 인스턴스**다(DataKey 는 이름으로 인터닝).
     * 클래스를 직접 참조하지 않는 이유는 그쪽이 플랫폼 내부라 버전마다 위치가 흔들리기 때문.
     */
    private fun anyEditor(e: AnActionEvent): Editor? =
        e.getData(TERMINAL_EDITOR) ?: e.getData(CommonDataKeys.EDITOR)

    private fun selectedText(e: AnActionEvent): String? =
        anyEditor(e)?.selectionModel?.selectedText?.trim()?.takeIf { it.isNotEmpty() }

    private fun truncate(text: String): String =
        if (text.length > SNIPPET_MAX) text.take(SNIPPET_MAX) + "\n…(truncated)" else text

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

        return "Target: $path:$range\n```\n${truncate(selection.selectedText.orEmpty())}\n```"
    }

    companion object {
        private val TERMINAL_EDITOR: DataKey<Editor> = DataKey.create("TERMINAL_EDITOR")
        private const val TOOL_WINDOW = "Claude Task Queue"
        private const val SNIPPET_MAX = 2000
    }
}
