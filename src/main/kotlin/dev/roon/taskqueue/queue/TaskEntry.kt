package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState

/** 실행 방식 */
enum class ExecMode {
    /** IntelliJ 터미널에서 대화형 실행 — 화면이 보이고 권한 승인·개입 가능 */
    TERMINAL,

    /** 백그라운드 헤드리스 실행 — 화면 없음, 무인 진행에 적합 */
    HEADLESS,
}

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

    /** 아직 실행 전이라 순서가 의미 있는 상태 */
    val isOrdered: Boolean get() = this == TODO || this == QUEUED
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

    /** 실행 방식 — 터미널(대화형, 개입 가능) vs 헤드리스(백그라운드) */
    var execMode: ExecMode = ExecMode.TERMINAL

    /** 실행할 터미널 탭 이름. 비면 새 탭을 만든다 */
    var terminalTab: String = ""

    /**
     * "새 대화" 로 한꺼번에 올린 묶음의 식별자.
     * 같은 묶음은 첫 작업이 연 탭을 물려받아 **한 대화방에서 직렬로** 돈다.
     */
    var batchId: String? = null

    /**
     * 우리가 Stop 훅을 심어 띄운 세션 ID. 같은 탭에 이어 보낼 때 이 ID 로 완료 신호를 매칭한다.
     * 비어 있으면 우리가 띄운 세션이 아니라는 뜻 — 완료 판정 불가.
     */
    var hookSessionId: String = ""

    var createdAt: Long = 0
    var startedAt: Long? = null
    var finishedAt: Long? = null

    var exitCode: Int? = null
    var costUsd: Double? = null
    var errorMessage: String? = null

    /** 재시도 횟수 (최초 실행 포함) */
    var attempts: Int = 0

    /** 큐 소진 요약 알림에 이미 포함됐는지 — 같은 건을 두 번 세지 않는다 */
    var notified: Boolean = false

    /** claude 의 마지막 답변. 터미널을 열지 않고도 결과를 알 수 있게 카드에 보여준다 */
    var summary: String? = null

    /** 종료 시점의 jsonl 판정 결과 — 프로세스 종료와 별개로 기록 */
    var finalState: SessionState = SessionState.UNKNOWN

    constructor(id: String, prompt: String, cwd: String, createdAt: Long) : this() {
        this.id = id
        this.prompt = prompt
        this.cwd = cwd
        this.createdAt = createdAt
    }

    fun shortLabel(): String = prompt.replace(Regex("\\s+"), " ").trim().take(60)

    /**
     * 한 줄로 펴서 [max] 자까지. **잘리면 말줄임을 붙인다** — 잘렸는지 눈에 보여야
     * 전문을 볼 생각을 한다(카드는 툴팁에 전문을 들고 있다).
     *
     * [shortLabel] 과 따로 두는 이유: 그것은 알림 제목·터미널 탭 이름에도 쓰여
     * 길이를 늘리면 그쪽이 지저분해진다.
     */
    fun excerpt(max: Int): String {
        val flat = prompt.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }
}
