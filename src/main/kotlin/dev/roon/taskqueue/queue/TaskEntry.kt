package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState

enum class TaskStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELED,
    ;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELED
}

/**
 * 큐 항목. XML 직렬화 대상이므로 no-arg 생성자 + var 프로퍼티.
 * repo 경로를 필드로 갖는다 — 크로스 프로젝트(N개 repo)도 같은 모델로 전개된다.
 */
class TaskEntry() {
    var id: String = ""
    var prompt: String = ""
    var cwd: String = ""

    /** 워크트리/브랜치 기준점. 미지정이면 각 repo 의 현재 기본 브랜치 */
    var baseBranch: String? = null

    var status: TaskStatus = TaskStatus.QUEUED

    /** 플러그인이 지정한 세션 ID — jsonl 경로 추적용 */
    var sessionId: String? = null

    var createdAt: Long = 0
    var startedAt: Long? = null
    var finishedAt: Long? = null

    var exitCode: Int? = null
    var costUsd: Double? = null
    var errorMessage: String? = null

    /** 재시도 횟수 (최초 실행 포함) */
    var attempts: Int = 0

    /** 종료 시점의 jsonl 판정 결과 — 프로세스 종료와 별개로 기록 */
    var finalState: SessionState = SessionState.UNKNOWN

    constructor(id: String, prompt: String, cwd: String, createdAt: Long) : this() {
        this.id = id
        this.prompt = prompt
        this.cwd = cwd
        this.createdAt = createdAt
    }

    fun shortLabel(): String = prompt.replace(Regex("\\s+"), " ").trim().take(60)
}
