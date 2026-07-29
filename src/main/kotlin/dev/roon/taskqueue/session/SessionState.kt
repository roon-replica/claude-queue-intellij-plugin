package dev.roon.taskqueue.session

/**
 * 세션 jsonl 의 마지막 "메시지" 엔트리로 판정한 상태.
 * UNKNOWN = 판단할 엔트리가 없음(빈 파일 / fromOffset 이후 신규 없음).
 */
enum class SessionState {
    /** 모델이 도는 중 (또는 tool_use 진행) */
    WORKING,

    /** 턴 종료 — 자동작업 완료 판정 근거 */
    DONE,

    /** AskUserQuestion / ExitPlanMode 로 사용자 응답 대기 */
    WAITING,

    /** 사용자가 ESC 로 인터럽트해 멈춤 */
    IDLE,

    UNKNOWN,
    ;

    val isTerminal: Boolean get() = this == DONE || this == IDLE
}
