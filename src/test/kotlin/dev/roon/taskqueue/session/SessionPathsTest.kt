package dev.roon.taskqueue.session

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SessionPathsTest {

    @Test
    fun `cwd 인코딩은 영문숫자 외를 하이픈으로`() {
        assertEquals(
            "-Users-mac-al03228536-IdeaProjects-claude-talk",
            SessionPaths.encodeCwd("/Users/local-user/IdeaProjects/claude-talk"),
        )
    }

    @Test
    fun `점과 슬래시 모두 하이픈`() {
        assertEquals("-private-tmp", SessionPaths.encodeCwd("/private/tmp"))
        assertEquals("-a-b-c", SessionPaths.encodeCwd("/a.b/c"))
    }

    @Test
    fun `세션 파일 경로 조합`() {
        val f = SessionPaths.sessionFile("/private/tmp", "abc-123")
        assertEquals("abc-123.jsonl", f.name)
        assertEquals("-private-tmp", f.parentFile.name)
    }
}
