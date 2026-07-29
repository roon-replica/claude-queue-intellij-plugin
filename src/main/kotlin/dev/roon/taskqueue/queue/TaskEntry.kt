package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState

/**
 * claude-talk 의 todo / inprogress / done 모델을 따른다.
 * **추가하면 TODO 로 들어가고, QUEUED(=inprogress)로 옮길 때 실행된다.**
 */
enum class TaskStatus {
    /** 적어만 둔 상태 — 실행 대상이 아니다 */
    TODO,

    /** 진행 대기줄에 올라감 — 순서가 오면 실행된다 */
    QUEUED,

    RUNNING,
    DONE,
    FAILED,
    CANCELED,
    ;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELED

    /** 실행 대기줄에 있거나 도는 중 */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING
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

    var status: TaskStatus = TaskStatus.TODO

    /** 플러그인이 지정한 세션 ID — jsonl 경로 추적용 */
    var sessionId: String? = null

    /**
     * 레인 이름. 빈 값이면 이 작업만의 새 세션(독립 실행).
     * 같은 레인의 작업들은 한 세션을 이어 쓰므로 앞 작업의 대화를 기억한다
     * (claude-talk 의 "방" 에 대응).
     */
    var lane: String = ""

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
