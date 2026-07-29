package dev.roon.taskqueue.nav

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRefsTest {

    private lateinit var base: File

    @BeforeEach
    fun setUp() {
        base = createTempDirectory().toFile()
        File(base, "src/main").mkdirs()
        File(base, "src/main/Foo.kt").writeText("fun foo() {}")
        File(base, "README.md").writeText("# hi")
    }

    @Test
    fun `경로와 라인을 뽑는다`() {
        val refs = FileRefs.extract("문제는 src/main/Foo.kt:12 에 있다", base)
        assertEquals(listOf(FileRefs.Ref("src/main/Foo.kt", 12)), refs)
    }

    @Test
    fun `라인 없는 경로도 뽑는다`() {
        val refs = FileRefs.extract("README.md 를 고쳤다", base)
        assertEquals(listOf(FileRefs.Ref("README.md", null)), refs)
    }

    @Test
    fun `존재하지 않는 경로는 버린다`() {
        assertTrue(FileRefs.extract("src/main/Nope.kt:3 참고", base).isEmpty())
    }

    @Test
    fun `버전 문자열 같은 잡음은 걸러진다`() {
        assertTrue(FileRefs.extract("Gradle 9.6.1 로 올렸다", base).isEmpty())
    }

    @Test
    fun `중복은 한 번만`() {
        val refs = FileRefs.extract("README.md 와 README.md 를", base)
        assertEquals(1, refs.size)
    }

    @Test
    fun `같은 파일의 다른 라인은 별개`() {
        val refs = FileRefs.extract("src/main/Foo.kt:1 과 src/main/Foo.kt:9", base)
        assertEquals(2, refs.size)
    }

    @Test
    fun `절대경로는 baseDir 하위면 상대경로로 정규화`() {
        val abs = File(base, "README.md").absolutePath
        assertEquals(listOf(FileRefs.Ref("README.md", null)), FileRefs.extract(abs, base))
    }

    @Test
    fun `resolve 는 절대경로를 돌려준다`() {
        val ref = FileRefs.Ref("README.md", 3)
        assertEquals(File(base, "README.md").absolutePath, FileRefs.resolve(ref, base).absolutePath)
    }

    @Test
    fun `limit 을 넘지 않는다`() {
        val text = (1..10).joinToString(" ") { "README.md:$it" }
        assertEquals(3, FileRefs.extract(text, base, limit = 3).size)
    }
}
