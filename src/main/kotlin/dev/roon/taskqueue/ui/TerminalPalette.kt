package dev.roon.taskqueue.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import dev.roon.taskqueue.terminal.TerminalTabs
import java.awt.MouseInfo

/**
 * "어느 터미널에서 돌릴까" 팔레트. **한 군데에만 둔다** —
 * 보드에서 실행할 때와 [QuickRunPopup] 이 같은 목록·같은 경고를 보여야 한다.
 */
object TerminalPalette {

    const val NEW_TERMINAL = "New terminal tab"
    private const val DIALOG_TITLE = "Claude Task Queue"

    /**
     * @param extraItems 목록 끝에 붙일 항목 (예: "TODO 에 쌓기"). 고르면 [onExtra] 로 온다
     * @param preselectLabel 미리 골라둘 탭의 **레지스트리 라벨**. 화면 문구는 세션 상태에 따라
     *   바뀌므로 문구가 아니라 라벨로 찾아야 한다
     * @param onChosen 선택된 탭 라벨 ("" = 새 탭)
     */
    fun choose(
        project: Project,
        title: String = "Run in terminal",
        anchor: RelativePoint? = null,
        preselectLabel: String? = null,
        extraItems: List<String> = emptyList(),
        onExtra: (String) -> Unit = {},
        onChosen: (String) -> Unit,
    ) {
        val tabs = TerminalTabs.list(project)

        // 고를 탭이 하나도 없고 대안 항목도 없으면 물을 것이 없다 — 조용히 새 탭으로
        if (tabs.isEmpty() && extraItems.isEmpty()) {
            warnIfUnusable(project)
            onChosen("")
            return
        }

        val items = listOf(NEW_TERMINAL) + tabs.map { it.display } + extraItems
        val builder = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(title)
            .setMovable(false)
            .setResizable(false)
            .setItemChosenCallback { chosen ->
                when {
                    chosen in extraItems -> onExtra(chosen)
                    // 고른 탭을 레지스트리에 고정한다 — 제목이 바뀌어도 위젯 참조로 찾는다
                    else -> onChosen(
                        tabs.firstOrNull { it.display == chosen }?.let(TerminalTabs::pin) ?: ""
                    )
                }
            }

        // 없어진 탭을 가리키면 안 된다 — 지금 목록에 실제로 있을 때만 미리 고른다
        tabs.firstOrNull { it.registered?.label == preselectLabel }
            ?.let { builder.setSelectedValue(it.display, true) }

        val popup = builder.createPopup()
        val at = anchor ?: cursorPoint()
        if (at != null) popup.show(at) else popup.showInFocusCenter()
    }

    /**
     * 탭이 있는데 하나도 못 쓰는 상황과 아예 없는 상황은 다르다 —
     * 조용히 새 탭을 열면 왜 내 터미널이 무시됐는지 알 길이 없다.
     */
    private fun warnIfUnusable(project: Project) {
        if (!TerminalTabs.hasUnusableTabs(project)) return
        Messages.showWarningDialog(
            project,
            "Your open terminal tabs can't be used with the Reworked terminal engine.\n\n" +
                "Switch it in Settings → Tools → Terminal → Terminal engine → Classic,\n" +
                "then restart the IDE. A new tab will be opened for now.",
            DIALOG_TITLE,
        )
    }

    /** 마우스가 있는 곳 — 드래그·툴바 조작 모두 커서 근처가 자연스럽다 */
    fun cursorPoint(): RelativePoint? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { RelativePoint.fromScreen(it) }
    }.getOrNull()
}
