package com.vam.demov.tilt

/**
 * TiltViewController 的配置参数。
 * 所有参数均有合理默认值，可直接使用 TiltViewConfig() 构造。
 */
data class TiltViewConfig(

    /**
     * 各角度区间的触发容差（单位：度）。
     * 触发范围 = 目标角度 ± zoneTolerance。
     * 例如默认值 20f 表示：左倾 90° 区间的实际触发范围为 70° ~ 110°。
     */
    val zoneTolerance: Float = 20f,

    /**
     * 迟滞防抖容差（单位：度）。
     * 离开当前区间时，需要额外偏移 hysteresis 才判定为切换，防止在区间边界抖动时反复触发动画。
     * 例如默认值 5f 表示：进入左倾 90° 区间需 > 70°，退出则需 < 65°。
     */
    val hysteresis: Float = 5f,

    /**
     * 低通滤波平滑系数，范围建议 0.05f ~ 0.3f。
     * 值越小传感器数据越平滑但响应越慢，值越大响应越灵敏但抖动越明显。
     * 公式：smoothed += (raw - smoothed) * smoothFactor
     */
    val smoothFactor: Float = 0.15f,

    /**
     * 区间切换时 View 过渡动画的时长（单位：毫秒）。
     * 动画使用 ViewPropertyAnimator + DecelerateInterpolator。
     */
    val animDuration: Long = 300L,

    /**
     * 状态切换延迟确认时间（单位：毫秒）。
     * 候选状态需连续稳定达到该时长才真正触发动画，避免短时间快速倾斜导致频繁切换。
     * 设置为 0L 可关闭延迟确认（保持即时切换）。
     */
    val stateDebounceMs: Long = 160L
)
