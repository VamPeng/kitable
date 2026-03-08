package com.vam.demov.dialog

import android.app.Dialog
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Dialog 弹出后，驱动页面内目标 View 向上平移的控制器。
 *
 * 目标 View 与 Dialog 相互独立：目标 View 位于页面布局的 FrameLayout 活动区内，
 * Dialog 弹出时通过 [initialViewBottomY]（bindTarget 时记录的初始 bottom 屏幕坐标）
 * 与 Dialog 顶部坐标的差值，驱动目标 View 向上平移；Dialog 关闭时自动复位。
 *
 * 重要：Controller 内部独占 setOnShowListener / setOnDismissListener，
 * 调用方不能在 attachDialog 之后再次设置这两个监听，否则会互相覆盖。
 * 如需监听 show/dismiss 事件，通过 [setOnShowCallback] / [setOnDismissCallback] 注册回调。
 *
 * 使用示例：
 * ```kotlin
 * val controller = DialogTopLiftController()
 * controller.bindTarget(targetView)           // View 必须已在屏幕上可见
 * controller.setOnDismissCallback { tvStatus.text = "已关闭" }
 *
 * val dialog = buildDialog()
 * controller.attachDialog(dialog)
 * dialog.show()
 * ```
 */
class DialogTopLiftController(
    private val config: DialogTopLiftConfig = DialogTopLiftConfig()
){

    companion object {
        const val TAG = "DialogTopLiftCtrl"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 内部状态
    // -------------------------------------------------------------------------

    /** 需要随 Dialog 上移的目标 View */
    private var targetView: View? = null

    /**
     * 目标 View 的初始 bottom 屏幕 Y 坐标（translationY=0 时）。
     * bindTarget 时延迟一帧读取并固定记录，后续所有 deltaY 基于此值计算，
     * 避免多次弹出 Dialog 时因实时读取 translationY 叠加导致位移错误。
     */
    private var initialViewBottomY: Float = 0f

    /** 当前附加的 Dialog */
    private var attachedDialog: Dialog? = null

    /** Dialog show 回调（供调用方更新 UI 状态） */
    private var onShowCallback: (() -> Unit)? = null

    /** Dialog dismiss 回调（供调用方更新 UI 状态） */
    private var onDismissCallback: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // 对外 API
    // -------------------------------------------------------------------------

    /**
     * 绑定目标 View（位于页面布局的 FrameLayout 活动区内）。
     *
     * 调用时 View 必须已在屏幕上可见（已完成 layout 和绘制），
     * 否则读取到的初始坐标为 0，后续平移计算将不正确。
     *
     * 绑定完成后若已有 Dialog 处于展示中，将立即触发一次上移，使目标 View 对齐 Dialog 顶部。
     */
    fun bindTarget(view: View): DialogTopLiftController {
        targetView = view
        // 延迟一帧读取，确保当前帧 layout/draw 均已完成后再记录坐标
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            initialViewBottomY = (loc[1] + view.height).toFloat()
            logD(TAG, "bindTarget: 初始位置记录完成, initialViewBottomY=$initialViewBottomY")

            // 若绑定时 Dialog 已在展示中，立即触发上移对齐
            val dialog = attachedDialog
            if (dialog != null && dialog.isShowing) {
                logD(TAG, "bindTarget: 检测到 Dialog 已展示，立即触发上移")
                triggerLift(dialog)
            }
        }
        logD(TAG, "bindTarget: 绑定成功，等待下一帧记录初始坐标")
        return this
    }

    /**
     * 注册 Dialog 展示回调（替代直接设置 setOnShowListener）。
     *
     * 在 attachDialog 之前或之后调用均可。
     */
    fun setOnShowCallback(callback: () -> Unit): DialogTopLiftController {
        onShowCallback = callback
        return this
    }

    /**
     * 注册 Dialog 关闭回调（替代直接设置 setOnDismissListener）。
     *
     * 在 attachDialog 之前或之后调用均可。
     */
    fun setOnDismissCallback(callback: () -> Unit): DialogTopLiftController {
        onDismissCallback = callback
        return this
    }

    /**
     * 将 Dialog 附加到 Controller，独占注册 show/dismiss 监听。
     *
     * - show 时：延迟一帧读取 Dialog 顶部坐标，基于 [initialViewBottomY] 计算 deltaY，驱动目标 View 上移
     * - dismiss 时：复位目标 View，并触发 [onDismissCallback]
     *
     * 警告：attachDialog 后不可再对同一 Dialog 调用 setOnShowListener / setOnDismissListener，
     * 否则会覆盖 Controller 内部监听导致功能失效。
     */
    fun attachDialog(dialog: Dialog): DialogTopLiftController {
        attachedDialog = dialog

        dialog.setOnShowListener {
            onShowCallback?.invoke()
            val decorView = dialog.window?.decorView
            if (decorView == null) {
                logE(TAG, "attachDialog: 无法获取 Dialog decorView")
                return@setOnShowListener
            }
            if (initialViewBottomY == 0f) {
                logE(TAG, "attachDialog: initialViewBottomY 未初始化，请先调用 bindTarget 并确保 View 可见")
                return@setOnShowListener
            }
            // post 延迟一帧，确保窗口尺寸与位置调整完成后再读取坐标
            decorView.post { triggerLift(dialog) }
        }

        dialog.setOnDismissListener {
            logD(TAG, "attachDialog: onDismiss, 开始复位")
            resetDown()
            attachedDialog = null
            onDismissCallback?.invoke()
        }

        logD(TAG, "attachDialog: 附加成功")
        return this
    }

    /**
     * 手动触发上移（通常由 attachDialog 自动调用，特殊场景可直接调用）。
     *
     * @param deltaY 向上平移的像素距离（正值 = 向上移动，内部转为绝对 translationY(-deltaY)）
     */
    fun liftUp(deltaY: Float): DialogTopLiftController {
        val target = targetView ?: run {
            logE(TAG, "liftUp: targetView 未绑定")
            return this
        }
        target.animate().cancel()
        target.animate()
            // 使用绝对定位（非 By），避免多次弹出时 translationY 累积叠加
            .translationY(-deltaY)
            .setDuration(config.animDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .withStartAction { logD(TAG, "liftUp: 动画开始, deltaY=$deltaY") }
            .withEndAction { logI(TAG, "liftUp: 动画结束, translationY=${target.translationY}") }
            .start()
        return this
    }

    /**
     * 复位目标 View 到初始位置（translationY = 0）。
     */
    fun resetDown(): DialogTopLiftController {
        val target = targetView ?: run {
            logE(TAG, "resetDown: targetView 未绑定")
            return this
        }
        target.animate().cancel()
        target.animate()
            .translationY(0f)
            .setDuration(config.resetDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { logI(TAG, "resetDown: 复位完成") }
            .start()
        return this
    }

    /**
     * 释放所有引用，避免内存泄漏。建议在 Activity.onDestroy() 中调用。
     */
    fun release() {
        targetView?.animate()?.cancel()
        targetView = null
        initialViewBottomY = 0f
        attachedDialog = null
        onShowCallback = null
        onDismissCallback = null
        logI(TAG, "release: 资源已释放")
    }

    /**
     * 当前是否已绑定目标 View。
     */
    fun isBound(): Boolean = targetView != null

    // -------------------------------------------------------------------------
    // 内部方法
    // -------------------------------------------------------------------------

    /**
     * 读取 Dialog 顶部坐标，基于 [initialViewBottomY] 计算 deltaY 并触发上移。
     *
     * deltaY = initialViewBottomY - dialogTopY
     * 始终以初始位置为基准，与当前 translationY 无关，消除叠加风险。
     */
    private fun triggerLift(dialog: Dialog) {
        val decorView = dialog.window?.decorView ?: run {
            logE(TAG, "triggerLift: 无法获取 Dialog decorView")
            return
        }
        val dialogLocation = IntArray(2)
        decorView.getLocationOnScreen(dialogLocation)
        val dialogTopY = dialogLocation[1].toFloat()

        // deltaY 基于初始位置计算，不受当前 translationY 影响
        val deltaY = initialViewBottomY - dialogTopY

        logD(TAG, "triggerLift: dialogTopY=$dialogTopY, initialViewBottomY=$initialViewBottomY, deltaY=$deltaY")

        if (deltaY <= 0) {
            logD(TAG, "triggerLift: View 已在 Dialog 顶部之上，无需平移")
            return
        }
        liftUp(deltaY)
    }
}
