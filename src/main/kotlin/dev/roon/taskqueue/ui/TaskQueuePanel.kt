package dev.roon.taskqueue.ui

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.roon.taskqueue.cli.ClaudeCli
import dev.roon.taskqueue.cli.StreamEvent
import dev.roon.taskqueue.session.ContextUsage
import dev.roon.taskqueue.session.SessionPaths
import dev.roon.taskqueue.session.SessionScanner
import dev.roon.taskqueue.session.SessionWatcher
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.util.UUID
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 0단계 검증용 패널 — 프롬프트 1건을 헤드리스로 실행하고 스트림을 표시한다.
 * 큐 보드(2B)는 이 패널을 대체할 예정.
 */
class TaskQueuePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val cli = ClaudeCli.getInstance()
    private val promptField = JBTextField("say OK")
    private val runButton = JButton("Run")
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val statusLabel = JBLabel()
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = true
    }

    private var handler: OSProcessHandler? = null
    private var watch: SessionWatcher.Handle? = null

    init {
        border = JBUI.Borders.empty(8)

        val top = JPanel(BorderLayout(8, 0)).apply {
            add(promptField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(runButton)
                add(cancelButton)
            }, BorderLayout.EAST)
        }

        add(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)

        runButton.addActionListener { start() }
        cancelButton.addActionListener { stop() }

        refreshCliStatus()
    }

    /** CLI 미설치 시 실행을 막고 안내한다 (worklist 0-5) */
    private fun refreshCliStatus() {
        val exe = cli.findExecutable()
        if (exe == null) {
            statusLabel.text = "claude CLI 를 찾을 수 없다 — https://claude.com/claude-code 에서 설치 후 IDE 재시작"
            runButton.isEnabled = false
        } else {
            val version = cli.version() ?: "version 확인 실패"
            statusLabel.text = "claude: ${exe.absolutePath}  ($version)"
            runButton.isEnabled = true
        }
    }

    private fun start() {
        val prompt = promptField.text?.trim().orEmpty()
        if (prompt.isEmpty()) return

        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        // 세션 ID 를 우리가 정한다 — jsonl 경로를 미리 알아야 상태 판정을 붙일 수 있다
        val sessionId = UUID.randomUUID().toString()
        val sessionFile = SessionPaths.sessionFile(workDir.absolutePath, sessionId)
        val fromOffset = if (sessionFile.isFile) sessionFile.length() else 0L

        output.text = ""
        append("$ claude -p \"$prompt\"  (cwd: ${workDir.absolutePath})\n")
        append("session: $sessionId\njsonl: ${sessionFile.absolutePath}\n\n")
        setRunning(true)

        handler = cli.run(
            prompt = prompt,
            workDir = workDir,
            sessionId = sessionId,
            onEvent = { event -> ui { render(event) } },
            onFinish = { exitCode ->
                ui {
                    append("\n— 프로세스 종료 (exit $exitCode)\n")
                    reportFinalState(sessionFile)
                    setRunning(false)
                }
            },
        )

        // 1단계 판정기를 실제로 붙여본다 (2A-4 완료 판정의 전신)
        watch = SessionWatcher.getInstance().watch(sessionFile, fromOffset) { state ->
            ui { append("[state] $state\n") }
        }
    }

    private fun stop() {
        watch?.cancel()
        handler?.destroyProcess()
        append("\n— 취소 요청\n")
    }

    /** 종료 후 최종 판정 + 컨텍스트 사용량 표시 */
    private fun reportFinalState(sessionFile: File) {
        watch?.cancel()
        if (!sessionFile.isFile) {
            append("[state] jsonl 없음 — 경로 규칙 확인 필요\n")
            return
        }
        val state = SessionScanner.sessionState(sessionFile)
        val model = SessionScanner.lastModel(sessionFile)
        val tokens = SessionScanner.lastContextTokens(sessionFile)
        append("[final] $state  model=$model  ctx=${ContextUsage.label(tokens, model)}\n")
        SessionScanner.lastAssistantText(sessionFile).takeIf { it.isNotEmpty() }
            ?.let { append("[last] $it\n") }
    }

    private fun render(e: StreamEvent) {
        when {
            e.type == "system" && e.subtype == "init" ->
                append("[session] ${e.sessionId}  cwd=${e.cwd}\n")

            e.assistantText != null ->
                append("${e.assistantText}\n")

            e.type == "rate_limit_event" ->
                append("[rate limit] ${e.rateLimitStatus}\n")

            e.isResult -> {
                val cost = e.totalCostUsd?.let { "$%.4f".format(it) } ?: "-"
                append("\n[result] error=${e.isError}  ${e.durationMs}ms  cost=$cost\n")
            }
        }
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        cancelButton.isEnabled = running
        promptField.isEnabled = !running
    }

    private fun append(text: String) {
        output.append(text)
        output.caretPosition = output.document.length
    }

    /** 프로세스 리스너는 EDT 밖에서 호출된다 — UI 갱신은 반드시 EDT 로 */
    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    override fun dispose() {
        watch?.cancel()
        handler?.destroyProcess()
        handler = null
    }
}
