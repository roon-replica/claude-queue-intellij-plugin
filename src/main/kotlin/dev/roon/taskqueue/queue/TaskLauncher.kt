package dev.roon.taskqueue.queue

import dev.roon.taskqueue.session.SessionState

/** 실행 결과 — 프로세스 종료 코드와 jsonl 판정을 함께 담는다 */
data class TaskResult(
    val exitCode: Int,
    val finalState: SessionState,
    val costUsd: Double?,
    val errorMessage: String?,
)

/** 실행 중 작업 취소 핸들 */
interface RunningTask {
    fun cancel()
}

/**
 * 작업 1건 실행 추상화. 기본 구현은 claude CLI 를 헤드리스로 띄운다.
 * 큐 로직을 플랫폼 없이 테스트하기 위해 인터페이스로 분리.
 */
interface TaskLauncher {
    fun launch(
        task: TaskEntry,
        onLine: (String) -> Unit,
        /** 모델이 낸 사람용 텍스트 — file:line 추출 대상 */
        onText: (String) -> Unit,
        onState: (SessionState) -> Unit,
        onDone: (TaskResult) -> Unit,
    ): RunningTask
}
