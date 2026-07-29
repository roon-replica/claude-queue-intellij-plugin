package dev.roon.taskqueue.nav

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

/** 작업 결과의 file:line 을 에디터로 연다 */
object FileNavigator {

    /**
     * @return 열기 성공 여부. 프로젝트 밖 파일도 열리지만 인덱싱은 되지 않는다.
     */
    fun open(project: Project, file: File, line: Int?): Boolean {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: VfsUtil.findFileByIoFile(file, true)
            ?: return false
        // 표시 라인은 1-base, API 는 0-base
        val zeroBased = ((line ?: 1) - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, vf, zeroBased, 0).navigate(true)
        return true
    }
}
