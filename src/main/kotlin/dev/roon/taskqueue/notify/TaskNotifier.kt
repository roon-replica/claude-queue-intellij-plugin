package dev.roon.taskqueue.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.roon.taskqueue.queue.TaskEntry
import dev.roon.taskqueue.queue.TaskStatus
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

    /** 개별 작업 종료. 실패는 눈에 걸려야 하므로 ERROR 로 띄운다 */
    override fun taskFinished(task: TaskEntry) {
        val label = task.shortLabel().ifEmpty { "task" }
        when (task.status) {
            TaskStatus.DONE -> notify("Task done", label, NotificationType.INFORMATION, task.cwd)
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

    private fun notify(title: String, body: String, type: NotificationType, cwd: String) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(title, body, type)
                .notify(projectFor(cwd))
        }
    }

    /** 알림을 그 작업의 프로젝트 창에 띄운다 — 못 찾으면 앱 수준으로 */
    private fun projectFor(cwd: String): Project? = runCatching {
        val target = File(cwd).absolutePath
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            p.basePath?.let { File(it).absolutePath } == target
        }
    }.getOrNull()
}
