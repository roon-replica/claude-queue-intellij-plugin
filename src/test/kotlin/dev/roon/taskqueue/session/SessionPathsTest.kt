package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class SessionPathsTest {

    @Test
    fun `cwd 인코딩은 영문숫자 외를 하이픈으로`() {
        assertEquals(
            "-Users-someone-IdeaProjects-claude-talk",
            SessionPaths.encodeCwd("/Users/someone/IdeaProjects/claude-talk"),
        )
    }

    @Test
    fun `점과 슬래시 모두 하이픈`() {
        assertEquals("-private-tmp", SessionPaths.encodeCwd("/private/tmp"))
        assertEquals("-a-b-c", SessionPaths.encodeCwd("/a.b/c"))
    }

    /** claude 는 실제 경로로 폴더명을 만든다 — 링크를 안 풀면 다른 폴더를 뒤지게 된다 */
    @Test
    fun `canonical 은 심볼릭 링크를 푼다`(@TempDir tmp: Path) {
        val real = tmp.resolve("real").also { Files.createDirectory(it) }
        val link = tmp.resolve("link")
        Files.createSymbolicLink(link, real)

        assertEquals(real.toFile().canonicalPath, SessionPaths.canonical(link.toString()))
        assertEquals(
            SessionPaths.encodeCwd(real.toFile().canonicalPath),
            SessionPaths.encodeCwd(SessionPaths.canonical(link.toString())),
        )
    }

    @Test
    fun `canonical 은 링크가 없으면 경로를 그대로 둔다`(@TempDir tmp: Path) {
        val dir = tmp.resolve("plain").also { Files.createDirectory(it) }
        assertEquals(dir.toFile().canonicalPath, SessionPaths.canonical(dir.toString()))
    }

    @Test
    fun `세션 파일 경로 조합`() {
        val f = SessionPaths.sessionFile("/private/tmp", "abc-123")
        assertEquals("abc-123.jsonl", f.name)
        assertEquals("-private-tmp", f.parentFile.name)
    }
}
