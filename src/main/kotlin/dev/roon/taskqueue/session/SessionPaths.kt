package dev.roon.taskqueue.session

import java.io.File

/**
 * 세션 jsonl 위치 규칙. `~/.claude/projects/<인코딩된 cwd>/<sessionId>.jsonl`
 */
object SessionPaths {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")

    val projectsRoot: File
        get() = File(System.getProperty("user.home"), ".claude/projects")

    /** cwd 의 영문/숫자 외 문자를 '-' 로 바꾼 폴더명 */
    fun encodeCwd(cwd: String): String = cwd.replace(NON_ALNUM, "-")

    fun projectDir(cwd: String): File = File(projectsRoot, encodeCwd(cwd))

    fun sessionFile(cwd: String, sessionId: String): File =
        File(projectDir(cwd), "$sessionId.jsonl")

    /** 해당 cwd 의 세션 파일 목록. subagents 하위는 제외 */
    fun listSessionFiles(cwd: String): List<File> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.toList() ?: emptyList()

    /**
     * [since] 이후에 갱신된 가장 최근 세션 파일.
     * 대화형 실행은 우리가 준 세션 ID 를 그대로 쓰지 않을 수 있어, ID 를 가정하지 않고 찾아낸다.
     */
    fun newestSessionFileSince(cwd: String, since: Long): File? =
        listSessionFiles(cwd)
            .filter { it.lastModified() >= since }
            .maxByOrNull { it.lastModified() }

    /** 파일명에서 세션 ID */
    fun sessionIdOf(file: File): String = file.name.removeSuffix(".jsonl")
}
