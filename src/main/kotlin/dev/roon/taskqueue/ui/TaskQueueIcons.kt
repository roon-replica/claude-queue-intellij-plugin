package dev.roon.taskqueue.ui

import com.intellij.openapi.util.IconLoader

/** 툴윈도 아이콘. 13x13 SVG, 다크 변형은 `_dark` 접미사로 자동 선택된다 */
object TaskQueueIcons {
    // plugin.xml 의 icon 속성은 static 필드를 찾는다 — @JvmField 없으면 해석 실패
    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/taskQueue.svg", TaskQueueIcons::class.java)
}
