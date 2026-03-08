package com.vam.demov.upload

import android.graphics.Color

/**
 * UploadProgressFragment 的可配置参数。
 *
 * 说明：
 * - 当前样式为“进度条底层铺满容器 + 前景信息层覆盖”。
 * - `progressWidthDp/progressHeightDp` 作用于容器尺寸，进度条始终 `match_parent` 填满容器。
 *
 * @param progressWidthDp 容器宽度（dp），小于等于 0 时使用 `match_parent`。
 * @param progressHeightDp 容器高度（dp），小于等于 0 时使用 `wrap_content`。
 * @param progressTrackColor 进度条底色。
 * @param progressFillColor 进度条前景色。
 * @param progressCornerRadiusDp 进度条圆角（dp）。
 * @param progressAnimationEnabled 默认是否启用进度动画。
 * @param progressAnimationDurationMs 满进度跨度（0 -> 100）对应的动画时长，局部跨度按比例缩放。
 * @param closeAnimationEnabled 点击关闭时默认是否启用关闭动画。
 * @param closeAnimationDurationMs 关闭动画时长。
 * @param disableProgressUpdateAfterClose 关闭后是否禁止后续进度更新。
 */
data class UploadProgressConfig(
    val progressWidthDp: Float = 0f,
    val progressHeightDp: Float = 44f,
    val progressTrackColor: Int = Color.parseColor("#D6DEE8"),
    val progressFillColor: Int = Color.parseColor("#1E88E5"),
    val progressCornerRadiusDp: Float = 6f,
    val progressAnimationEnabled: Boolean = true,
    val progressAnimationDurationMs: Long = 420L,
    val closeAnimationEnabled: Boolean = true,
    val closeAnimationDurationMs: Long = 220L,
    val disableProgressUpdateAfterClose: Boolean = true
)
