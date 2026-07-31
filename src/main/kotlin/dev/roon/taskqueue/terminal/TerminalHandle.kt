package dev.roon.taskqueue.terminal

import com.intellij.ui.content.Content

/**
 * 터미널 탭 하나에 대해 우리가 하는 일 전부. **엔진(Classic/Reworked) 차이를 여기서 흡수한다.**
 *
 * 신 엔진 API 는 `@ApiStatus.Experimental` 이라 예고 없이 바뀔 수 있다. 구현을 갈라두면
 * 그때 폭발 반경이 그 구현 하나로 제한되고, 구버전 IDE 에서는 아예 로드하지 않을 수도 있다.
 * 그래서 **신 API 타입이 이 인터페이스 밖으로 새면 안 된다.**
 *
 * 동일성은 이 객체의 참조로 판단한다 — 플랫폼 위젯은 어댑터를 매번 새로 만드는 경우가 있어
 * (`asNewWidget()`, 실측) 참조 비교가 깨진다. 탭을 Content 로 찾아야 할 때는 [matches] 로 묻는다.
 */
interface TerminalHandle {

    /** tty 가 붙었는지. 붙기 전에는 명령을 넣어도 유실된다 */
    val ready: Boolean

    /** 탭이 아직 살아있는지 (사람이 닫았으면 false) */
    val alive: Boolean

    /**
     * 대화형 입력창에 한 줄 밀어넣는다.
     * **먼저 입력창을 비운다** — 치다 만 글자가 남아 있으면 이어붙어 엉뚱한 지시가 된다.
     */
    fun write(text: String): Result<Unit>

    /** 셸에서 명령을 실행한다 (claude 를 띄울 때). 셸 조작이 안 되는 탭이면 실패 */
    fun runCommand(command: String): Result<Unit>

    /**
     * 이 탭에서 **claude 가 실제로 돌고 있는지.**
     * @return null = 들여다볼 수 없음. **null 을 "돈다" 로 해석하면 안 된다** —
     *   빈 셸에 자연어 프롬프트를 타이핑하는 사고가 난다(실측)
     */
    fun claudeRunning(): Boolean?

    /**
     * 그 탭에서 무언가 돌고 있는지 (claude 가 아닌 남의 작업 보호용).
     * @return null = 판별 불가
     */
    fun hasRunningCommand(): Boolean?

    fun requestFocus()

    /**
     * 사용자가 직접 바꾼 탭 이름. 안 바꿨으면 null.
     *
     * **앱이 바꾼 제목과 구분해서 읽는다** — claude 가 실행되면 탭 제목을 "✳ Claude Code" 로
     * 덮어쓰는데, 그것까지 따라가면 모든 claude 탭이 같은 이름이 된다.
     * 플랫폼이 둘을 따로 들고 있어(`TerminalTitle`) 사용자가 정한 것만 고른다.
     */
    fun userTitle(): String?

    /** 이 탭이 그 Content 인지 — 툴윈도에서 탭을 찾아 보여줄 때 쓴다 */
    fun matches(content: Content): Boolean
}
