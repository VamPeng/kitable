package com.vam.demov.permission

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.vam.demov.R

/**
 * 权限提示条 View。
 *
 * 独立复用的横条 UI 组件，不负责实际权限申请，仅展示提示信息并向外暴露操作回调。
 * 典型场景：与上传进度区域同位置叠放，根据权限状态独立显示/隐藏。
 *
 * --- 对外 API ---
 * [applyConfig]              设置样式配置（背景色、文字色、操作按钮文案）
 * [showTip]                  显示提示文案，支持滑入动画
 * [hideTip]                  隐藏提示条，支持滑出动画
 * [setOnActionClickListener] 注册"去授权"等操作按钮的点击回调
 * [isShowing]                当前是否处于显示状态
 */
class PermissionTipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "PermissionTipView"
    }

    private fun logD(tag: String = TAG, content: String) = Log.d(tag, content)
    private fun logI(tag: String = TAG, content: String) = Log.i(tag, content)
    private fun logE(tag: String = TAG, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 子 View
    // -------------------------------------------------------------------------

    private val tvMessage: TextView
    private val tvAction: TextView

    // -------------------------------------------------------------------------
    // 状态
    // -------------------------------------------------------------------------

    private var config = PermissionTipConfig()
    private var onActionClickListener: (() -> Unit)? = null

    /** 是否处于显示状态（动画进行中也算） */
    private var showing = false

    /** 当前正在执行的动画句柄，用于在新动画开始前取消旧动画 */
    private var runningAnimator: ObjectAnimator? = null

    // -------------------------------------------------------------------------
    // 初始化
    // -------------------------------------------------------------------------

    init {
        LayoutInflater.from(context).inflate(R.layout.view_permission_tip, this, true)
        tvMessage = findViewById(R.id.tvPermissionMessage)
        tvAction = findViewById(R.id.tvPermissionAction)

        tvAction.setOnClickListener {
            logD(content = "Action button clicked")
            onActionClickListener?.invoke()
        }

        // 初始默认隐藏
        visibility = View.GONE
        applyConfig(config)
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 设置样式配置，调用后立即生效（不触发显示/隐藏动画）。
     * 可在 [showTip] 之前或之后调用。
     */
    fun applyConfig(config: PermissionTipConfig) {
        this.config = config
        setBackgroundColor(config.backgroundColor)
        tvMessage.setTextColor(config.textColor)
        if (config.actionText != null) {
            tvAction.text = config.actionText
            tvAction.setTextColor(config.actionTextColor)
            tvAction.visibility = View.VISIBLE
        } else {
            tvAction.visibility = View.GONE
        }
        logD(content = "applyConfig done")
    }

    /**
     * 显示权限提示条，使用淡入动画。
     *
     * 若已在显示中，仅更新文案，不重复执行动画。
     *
     * @param message  提示文案
     * @param animate  是否使用淡入动画，默认 true
     */
    fun showTip(message: String, animate: Boolean = true) {
        tvMessage.text = message
        if (showing) {
            logD(content = "showTip: already showing, message updated")
            return
        }
        showing = true
        logI(content = "showTip: \"$message\", animate=$animate")

        runningAnimator?.cancel()

        if (animate && config.animDurationMs > 0) {
            alpha = 0f
            visibility = View.VISIBLE
            val anim = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply {
                duration = config.animDurationMs
            }
            runningAnimator = anim
            anim.start()
        } else {
            alpha = 1f
            visibility = View.VISIBLE
        }
    }

    /**
     * 隐藏权限提示条，使用淡出动画。
     *
     * @param animate 是否使用淡出动画，默认 true
     */
    fun hideTip(animate: Boolean = true) {
        if (!showing) return
        showing = false
        logI(content = "hideTip: animate=$animate")

        runningAnimator?.cancel()

        if (animate && config.animDurationMs > 0) {
            val anim = ObjectAnimator.ofFloat(this, "alpha", alpha, 0f).apply {
                duration = config.animDurationMs
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    alpha = 1f
                }
            })
            runningAnimator = anim
            anim.start()
        } else {
            visibility = View.GONE
            alpha = 1f
        }
    }

    /**
     * 注册操作按钮（"去授权"等）的点击回调。
     * 实际权限申请逻辑由调用方实现，本 View 只负责触发回调。
     */
    fun setOnActionClickListener(listener: (() -> Unit)?) {
        onActionClickListener = listener
    }

    /**
     * 当前是否处于显示状态（含动画进行中）。
     */
    fun isShowing(): Boolean = showing
}
