package dev.roon.taskqueue.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import java.io.File

/**
 * 작업 결과 확인용 최소 git 연동. Git4Idea 의존 없이 CLI 직접 호출.
 * 워크트리 조작(3.3)은 post-MVP 이므로 여기서는 읽기만 한다.
 */
object GitDiffs {

    private const val TIMEOUT_MS = 10_000

    /** HEAD 대비 변경된 파일 (미추적 포함) */
    fun changedFiles(cwd: File): List<String> {
        val tracked = run(cwd, "diff", "--name-only", "HEAD").orEmpty()
        val untracked = run(cwd, "ls-files", "--others", "--exclude-standard").orEmpty()
        return (tracked + untracked)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    /** HEAD 시점의 파일 내용. 신규 파일이면 null */
    fun contentAtHead(cwd: File, path: String): String? {
        val cmd = GeneralCommandLine("git", "show", "HEAD:$path").apply {
            setWorkDirectory(cwd)
            withCharset(Charsets.UTF_8)
        }
        val out = runCatching { ExecUtil.execAndGetOutput(cmd, TIMEOUT_MS) }.getOrNull() ?: return null
        return if (out.exitCode == 0) out.stdout else null
    }

    fun isGitRepo(cwd: File): Boolean =
        run(cwd, "rev-parse", "--git-dir") != null

    private fun run(cwd: File, vararg args: String): List<String>? {
        val cmd = GeneralCommandLine("git", *args).apply {
            setWorkDirectory(cwd)
            withCharset(Charsets.UTF_8)
        }
        val out = runCatching { ExecUtil.execAndGetOutput(cmd, TIMEOUT_MS) }.getOrNull() ?: return null
        if (out.exitCode != 0) return null
        return out.stdoutLines
    }
}
