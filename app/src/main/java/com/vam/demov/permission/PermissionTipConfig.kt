package com.vam.demov.permission

/**
 * PermissionTipView 样式配置。
 *
 * @param textColor         提示文字颜色
 * @param actionText        操作按钮文字（null 则不显示操作按钮）
 * @param actionTextColor   操作按钮文字颜色
 * @param backgroundColor   提示条背景色
 * @param animDurationMs    显示/隐藏动画时长（ms），0 表示无动画
 */
data class PermissionTipConfig(
    /** 提示文字颜色，默认白色（搭配蓝色背景） */
    val textColor: Int = 0xFFFFFFFF.toInt(),
    /** 操作按钮文字（null 则不显示操作按钮） */
    val actionText: String? = "去授权",
    /** 操作按钮文字颜色，默认蓝色（搭配白色圆角按钮背景） */
    val actionTextColor: Int = 0xFF1E88E5.toInt(),
    /** 提示条背景色，默认蓝色 */
    val backgroundColor: Int = 0xFF1E88E5.toInt(),
    /** 显示/隐藏动画时长（ms），0 表示无动画 */
    val animDurationMs: Long = 250L,
)
