package dev.roon.taskqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.SystemNotifications
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskStatus
import dev.roon.taskqueue.session.SessionPaths
import java.io.File

/** 알림 창구 — 플랫폼 없는 테스트에서 갈아끼운다 */
interface TaskNotifications {
    fun taskFinished(task: TaskEntry)
    fun queueDrained(done: Int, failed: Int, cwd: String)
}

/**
 * 작업 결과를 IDE 알림으로 알린다.
 *
 * 자리를 비운 사이 큐가 진행되는 게 이 플러그인의 요점이라, 끝났는지 보러
 * 창을 들여다봐야 하면 의미가 반감된다.
 *
 * 플랫폼이 없는 환경(단위 테스트)에서도 호출되므로 실패는 조용히 삼킨다.
 */
object TaskNotifier : TaskNotifications {

    private const val GROUP = "Task Queue"

    /** 알림에 싣는 답변 길이 */
    private const val SUMMARY_MAX = 120

    /** 개별 작업 종료. 실패는 눈에 걸려야 하므로 ERROR 로 띄운다 */
    override fun taskFinished(task: TaskEntry) {
        val label = task.shortLabel().ifEmpty { "task" }
        when (task.status) {
            // 답변이 있으면 그걸 보여준다 — 알림만 보고도 결과를 알 수 있게
            TaskStatus.DONE -> notify(
                "Task done",
                task.summary?.let { "$label\n${oneLine(it).take(SUMMARY_MAX)}" } ?: label,
                NotificationType.INFORMATION,
                task.cwd,
            )
            TaskStatus.FAILED -> notify(
                "Task failed",
                listOfNotNull(label, task.errorMessage).joinToString(" — "),
                NotificationType.ERROR,
                task.cwd,
            )
            // 사용자가 직접 취소한 것이라 알릴 이유가 없다
            else -> Unit
        }
    }

    /**
     * 큐가 다 비었을 때의 요약. 자리를 비운 사람에게 가장 쓸모 있는 알림이다.
     * @param done 성공 건수, [failed] 실패 건수
     */
    override fun queueDrained(done: Int, failed: Int, cwd: String) {
        if (done + failed == 0) return
        val body = buildString {
            append(done).append(" done")
            if (failed > 0) append(", ").append(failed).append(" failed")
        }
        val type = if (failed > 0) NotificationType.WARNING else NotificationType.INFORMATION
        notify("Task queue finished", body, type, cwd)
    }

    /** 답변은 여러 줄일 수 있다 — 알림은 짧게 */
    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * OS 알림으로 띄우고, IDE 쪽에는 Event Log 기록만 남긴다.
     *
     * IDE 풍선은 작업하는 화면 위로 튀어나와 거슬린다. 반면 OS 알림은 IDE 가
     * 뒤에 있어도 보이므로, 자리를 비운 사이 큐가 도는 이 플러그인에 더 맞다.
     */
    private fun notify(title: String, body: String, type: NotificationType, cwd: String) {
        runCatching { SystemNotifications.getInstance().notify(GROUP, title, body) }
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(title, body, type)
                .notify(projectFor(cwd))
        }
    }

    /** 알림을 그 작업의 프로젝트 창에 띄운다 — 못 찾으면 앱 수준으로 */
    private fun projectFor(cwd: String): Project? = runCatching {
        // 양쪽 다 링크를 풀어 비교한다 — 한쪽만 풀면 같은 프로젝트를 못 알아본다
        val target = SessionPaths.canonical(cwd)
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            p.basePath?.let { SessionPaths.canonical(it) } == target
        }
    }.getOrNull()
}
