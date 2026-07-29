package dev.roon.taskqueue.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import java.io.File

/**
 * claude CLI 탐지 + 헤드리스 실행. 플러그인의 유일한 외부 의존.
 */
@Service
class ClaudeCli {

    /** PATH 우선, 실패 시 통상 설치 위치를 순회한다. */
    fun findExecutable(): File? {
        PathEnvironmentVariableUtil.findInPath("claude")?.let { if (it.canExecute()) return it }
        val home = System.getProperty("user.home")
        return FALLBACK_PATHS
            .map { File(it.replace("~", home)) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * `claude --version` 결과. 탐지/인증 안내 UI 에서 사용.
     * **EDT 에서 호출 금지** — 동기 프로세스 실행이라 플랫폼이 예외를 던진다.
     */
    fun version(): String? {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            thisLogger().warn("version() called on EDT — must run on a background thread")
            return null
        }
        val exe = findExecutable() ?: return null
        return try {
            val cmd = GeneralCommandLine(exe.absolutePath, "--version")
            ExecUtil.execAndGetOutput(cmd, VERSION_TIMEOUT_MS).stdout.trim().ifEmpty { null }
        } catch (e: Exception) {
            thisLogger().warn("claude --version failed", e)
            null
        }
    }

    /**
     * 프롬프트 1건을 비대화형으로 실행한다.
     * @param sessionId 지정하면 해당 세션으로 실행 — 추적/이어가기에 사용
     * @param resume 이미 존재하는 세션에 이어붙일 때 true.
     *   **`--session-id` 를 이미 쓴 세션에 다시 쓰면 "already in use" 로 실패한다** (실측 확인).
     *   이어가기는 반드시 `--resume` 이어야 한다.
     * @return 시작된 프로세스 핸들러. 취소는 destroyProcess() 로.
     */
    fun run(
        prompt: String,
        workDir: File,
        sessionId: String? = null,
        resume: Boolean = false,
        onEvent: (StreamEvent) -> Unit,
        onRawLine: (String) -> Unit = {},
        onFinish: (exitCode: Int) -> Unit = {},
    ): OSProcessHandler {
        val exe = findExecutable() ?: error("claude CLI not found")

        val cmd = GeneralCommandLine(exe.absolutePath).apply {
            addParameters("-p", prompt)
            // stream-json 은 print 모드에서 --verbose 를 함께 요구한다
            addParameters("--output-format", "stream-json", "--verbose")
            sessionId?.let {
                if (resume) addParameters("--resume", it) else addParameters("--session-id", it)
            }
            setWorkDirectory(workDir)
            withCharset(Charsets.UTF_8)
        }

        val handler = OSProcessHandler(cmd)
        handler.addProcessListener(LineSplittingListener(onEvent, onRawLine, onFinish))
        handler.startNotify()
        return handler
    }

    /** stdout 은 라인 경계로 도착하지 않는다 — 버퍼링해서 개행 단위로 자른다. */
    private class LineSplittingListener(
        private val onEvent: (StreamEvent) -> Unit,
        private val onRawLine: (String) -> Unit,
        private val onFinish: (Int) -> Unit,
    ) : ProcessAdapter() {
        private val buffer = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            buffer.append(event.text)
            while (true) {
                val nl = buffer.indexOf("\n")
                if (nl < 0) break
                val line = buffer.substring(0, nl)
                buffer.delete(0, nl + 1)
                if (line.isBlank()) continue
                onRawLine(line)
                StreamEvent.parse(line)?.let(onEvent)
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            // 개행 없이 끝난 잔여 라인 처리
            val rest = buffer.toString().trim()
            if (rest.isNotEmpty()) {
                onRawLine(rest)
                StreamEvent.parse(rest)?.let(onEvent)
            }
            onFinish(event.exitCode)
        }
    }

    companion object {
        private const val VERSION_TIMEOUT_MS = 5_000

        private val FALLBACK_PATHS = listOf(
            "~/.local/bin/claude",
            "~/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
        )

        fun getInstance(): ClaudeCli = service()
    }
}
