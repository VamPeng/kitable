package com.vam.demov.dialog

/**
 * DialogTopLiftController 配置。
 *
 * @param animDurationMs 目标 View 上移动画时长（毫秒）
 * @param resetDurationMs Dialog 关闭后复位动画时长（毫秒）
 * @param containerGravity 活动区 FrameLayout 在父布局中的位置：[ContainerGravity.TOP] 或 [ContainerGravity.BOTTOM]
 */
data class DialogTopLiftConfig(
    val animDurationMs: Long = 300L,
    val resetDurationMs: Long = 250L,
    val containerGravity: ContainerGravity = ContainerGravity.BOTTOM
) {
    /** 活动区 FrameLayout 相对父布局的位置 */
    enum class ContainerGravity { TOP, BOTTOM }
}
