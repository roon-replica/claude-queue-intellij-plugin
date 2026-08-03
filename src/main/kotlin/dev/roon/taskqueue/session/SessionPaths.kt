package dev.roon.taskqueue.session

import java.io.File

/**
 * 세션 jsonl 위치 규칙. `~/.claude/projects/<인코딩된 cwd>/<sessionId>.jsonl`
 */
object SessionPaths {

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")

    val projectsRoot: File
        get() = File(System.getProperty("user.home"), ".claude/projects")

    /**
     * 심볼릭 링크를 푼 실제 경로.
     *
     * **claude 는 전사 폴더명을 만들 때 cwd 를 실제 경로로 푼다** — `~/work` 가
     * `/Volumes/SSD/projects` 링크면 `-Volumes-SSD-projects-…` 로 저장한다.
     * IntelliJ 의 `basePath` 는 링크를 풀지 않으므로 그대로 인코딩하면 서로 다른
     * 폴더를 가리키게 되고, 전사를 영영 못 찾는다 (실측 확인).
     *
     * 링크가 없으면 입력과 같은 값이라 붙여서 손해가 없다.
     */
    fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** cwd 의 영문/숫자 외 문자를 '-' 로 바꾼 폴더명 */
    fun encodeCwd(cwd: String): String = cwd.replace(NON_ALNUM, "-")

    fun projectDir(cwd: String): File = File(projectsRoot, encodeCwd(cwd))

    fun sessionFile(cwd: String, sessionId: String): File =
        File(projectDir(cwd), "$sessionId.jsonl")

    /** 해당 cwd 의 세션 파일 목록. subagents 하위는 제외 */
    fun listSessionFiles(cwd: String): List<File> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.toList() ?: emptyList()

    /**
     * 최근에 쓴 세션 파일 [limit] 개.
     *
     * **mtime 으로 먼저 추린 뒤 내용을 읽는다** — mtime 은 파일을 열지 않고 얻으므로
     * 수십 개가 있어도 공짜다. 내용 읽기는 추려낸 것에만 든다.
     */
    fun recentSessionFiles(cwd: String, limit: Int): List<File> =
        listSessionFiles(cwd)
            .sortedByDescending { it.lastModified() }
            .take(limit)

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
