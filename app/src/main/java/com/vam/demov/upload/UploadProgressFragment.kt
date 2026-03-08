package com.vam.demov.upload

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import com.vam.demov.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 上传进度 UI 载体 Fragment。
 *
 * 当前样式：
 * - 进度条作为底层背景（铺满容器）
 * - 状态文案 / 缩略图 / 关闭按钮作为前景层覆盖在进度条上
 *
 * 对外能力：
 * - 支持进度动画更新到目标值
 * - 支持 close 后禁用进度更新并隐藏整体 View
 * - 支持上传完成态缩略图展示与点击回调透传
 *
 * 使用示例：
 * ```kotlin
 * val fragment = UploadProgressFragment.newInstance()
 * fragment.applyConfig(UploadProgressConfig())
 * fragment.setOnThumbnailClickListener { /* 调用方处理 */ }
 * fragment.updateProgress(35)
 * ```
 */
class UploadProgressFragment : Fragment(R.layout.fragment_upload_progress) {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    companion object {
        const val TAG = "UploadProgressFrag"

        /** 创建实例。 */
        fun newInstance(): UploadProgressFragment = UploadProgressFragment()
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 配置与 View 引用
    // -------------------------------------------------------------------------

    private var config: UploadProgressConfig = UploadProgressConfig()

    private var panelRoot: View? = null
    private var tvStatus: TextView? = null
    private var progressView: UploadProgressBarView? = null
    private var ivThumbnail: ImageView? = null
    private var ivClose: ImageView? = null

    // -------------------------------------------------------------------------
    // 运行时状态
    // -------------------------------------------------------------------------

    /** 当前已绘制到进度条上的进度（0f..1f）。 */
    private var currentProgress: Float = 0f

    /** 外部最新要求到达的目标进度（0f..1f）。 */
    private var targetProgress: Float = 0f

    private var isClosed: Boolean = false
    private var isCompleted: Boolean = false

    /** 是否已启动按帧平滑追赶任务。 */
    private var isProgressSmoothingRunning: Boolean = false

    /** 上一帧时间戳，用于按真实帧间隔计算追赶速度。 */
    private var lastProgressFrameTimeMs: Long = 0L

    // -------------------------------------------------------------------------
    // 延迟应用参数（View 尚未创建时暂存）
    // -------------------------------------------------------------------------

    private var pendingProgressPercent: Int? = null
    private var pendingAnimateUpdate: Boolean? = null
    private var pendingThumbnailResId: Int? = null

    // -------------------------------------------------------------------------
    // 对外回调
    // -------------------------------------------------------------------------

    private var onCloseClickListener: (() -> Unit)? = null
    private var onThumbnailClickListener: (() -> Unit)? = null

    /** 按帧平滑追赶进度的任务。 */
    private val progressFrameRunnable = object : Runnable {
        override fun run() {
            val bar = progressView ?: run {
                stopProgressSmoothing()
                return
            }

            val now = SystemClock.uptimeMillis()
            val deltaMs = if (lastProgressFrameTimeMs == 0L) 16L else (now - lastProgressFrameTimeMs).coerceAtLeast(1L)
            lastProgressFrameTimeMs = now

            val distance = targetProgress - currentProgress
            if (abs(distance) <= 0.002f) {
                currentProgress = targetProgress
                bar.setProgressFraction(currentProgress)
                applyStatusByProgress(currentProgress)
                stopProgressSmoothing()
                return
            }

            // 指数逼近：更新越频繁越不会反复“重启动画”，大步进时也会自然加速追赶。
            val blend = 1f - exp(-deltaMs / 110f)
            currentProgress += distance * blend
            bar.setProgressFraction(currentProgress)
            applyStatusByProgress(currentProgress)
            bar.postOnAnimation(this)
        }
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 应用配置（可在 view 创建前后调用）。
     *
     * 当 view 已就绪时会立即生效；否则在 onViewCreated 后自动应用。
     */
    fun applyConfig(config: UploadProgressConfig) {
        this.config = config
        applyConfigIfReady()
    }

    /**
     * 更新进度（0..100）。
     *
     * @param targetPercent 目标进度百分比。
     * @param animate 是否启用动画（不传则使用 config 默认值）。
     */
    fun updateProgress(targetPercent: Int, animate: Boolean = config.progressAnimationEnabled) {
        val clampedPercent = targetPercent.coerceIn(0, 100)

        if (isClosed && config.disableProgressUpdateAfterClose) {
            logI(TAG, "updateProgress: 已关闭，忽略进度更新 percent=$clampedPercent")
            return
        }

        if (view == null) {
            pendingProgressPercent = clampedPercent
            pendingAnimateUpdate = animate
            return
        }

        ensurePanelVisibleIfNeeded()
        val targetFraction = clampedPercent / 100f
        updateDisplayedProgress(targetFraction, animate)
    }

    /**
     * 标记上传完成（等价于进度到 100% 并展示缩略图）。
     */
    fun markUploadCompleted(animate: Boolean = config.progressAnimationEnabled) {
        updateProgress(100, animate)
    }

    /**
     * 关闭进度条 UI。
     *
     * 关闭后若配置 `disableProgressUpdateAfterClose=true`，后续 updateProgress 会被忽略。
     * @param enableAnimation 本次是否启用关闭动画（不传则使用 config 默认值）。
     */
    fun close(enableAnimation: Boolean = config.closeAnimationEnabled) {
        if (isClosed) {
            logD(TAG, "close: 已处于关闭状态，忽略重复调用")
            return
        }
        isClosed = true
        stopProgressSmoothing()

        val root = panelRoot ?: return
        if (enableAnimation) {
            root.animate().cancel()
            root.animate()
                .alpha(0f)
                .translationY(-dpToPx(8f))
                .setDuration(config.closeAnimationDurationMs)
                .withEndAction {
                    root.visibility = View.GONE
                    root.alpha = 1f
                    root.translationY = 0f
                    logI(TAG, "close: 关闭动画结束，View 已隐藏")
                }
                .start()
        } else {
            root.animate().cancel()
            root.visibility = View.GONE
            root.alpha = 1f
            root.translationY = 0f
            logI(TAG, "close: 无动画关闭，View 已隐藏")
        }
    }

    /**
     * 重置并显示为“上传中”状态。
     *
     * @param initialPercent 初始进度（0..100）。
     */
    fun resetAndShow(initialPercent: Int = 0) {
        val clampedPercent = initialPercent.coerceIn(0, 100)
        isClosed = false
        isCompleted = false
        stopProgressSmoothing()

        val root = panelRoot
        if (root != null) {
            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.translationY = 0f
        }

        val thumb = ivThumbnail
        if (thumb != null) {
            thumb.visibility = View.GONE
        }

        currentProgress = clampedPercent / 100f
        targetProgress = currentProgress
        progressView?.setProgressFraction(currentProgress)
        applyStatusByProgress(currentProgress)
    }

    /**
     * 设置缩略图资源（上传完成后显示）。
     */
    fun setThumbnailRes(@DrawableRes resId: Int) {
        if (view == null) {
            pendingThumbnailResId = resId
            return
        }
        ivThumbnail?.setImageResource(resId)
    }

    /**
     * 设置缩略图 Drawable（上传完成后显示）。
     */
    fun setThumbnailDrawable(drawable: Drawable?) {
        ivThumbnail?.setImageDrawable(drawable)
    }

    /** 设置关闭按钮点击监听。 */
    fun setOnCloseClickListener(listener: (() -> Unit)?) {
        onCloseClickListener = listener
    }

    /** 设置缩略图点击监听。 */
    fun setOnThumbnailClickListener(listener: (() -> Unit)?) {
        onThumbnailClickListener = listener
    }

    /** 当前是否已关闭。 */
    fun isClosed(): Boolean = isClosed

    /** 当前是否已完成。 */
    fun isCompleted(): Boolean = isCompleted

    // -------------------------------------------------------------------------
    // Fragment 生命周期
    // -------------------------------------------------------------------------

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        panelRoot = view.findViewById(R.id.uploadPanelRoot)
        tvStatus = view.findViewById(R.id.tvUploadStatus)
        progressView = view.findViewById(R.id.uploadProgressBar)
        ivThumbnail = view.findViewById(R.id.ivUploadThumbnail)
        ivClose = view.findViewById(R.id.ivUploadClose)

        ivClose?.setOnClickListener {
            onCloseClickListener?.invoke()
            close()
        }
        ivThumbnail?.setOnClickListener {
            if (isCompleted) {
                onThumbnailClickListener?.invoke()
            }
        }

        applyConfigIfReady()

        pendingThumbnailResId?.let {
            ivThumbnail?.setImageResource(it)
            pendingThumbnailResId = null
        }

        val deferredProgress = pendingProgressPercent
        if (deferredProgress != null) {
            val animate = pendingAnimateUpdate ?: config.progressAnimationEnabled
            pendingProgressPercent = null
            pendingAnimateUpdate = null
            updateProgress(deferredProgress, animate)
        } else {
            resetAndShow((currentProgress * 100f).roundToInt())
        }
    }

    override fun onDestroyView() {
        stopProgressSmoothing()
        panelRoot = null
        tvStatus = null
        progressView = null
        ivThumbnail = null
        ivClose = null
        super.onDestroyView()
    }

    // -------------------------------------------------------------------------
    // 内部：配置应用与动画
    // -------------------------------------------------------------------------

    /**
     * 应用配置到当前视图：
     * - 容器（panelRoot）使用 config 的宽高
     * - 进度条（progressView）始终 match_parent 铺满容器
     */
    private fun applyConfigIfReady() {
        val root = panelRoot
        val bar = progressView ?: return

        // 进度条始终铺满父容器；宽高配置作用于父容器本身。
        root?.layoutParams?.let { rootLp ->
            rootLp.width = if (config.progressWidthDp > 0f) {
                dpToPx(config.progressWidthDp).roundToInt()
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }

            rootLp.height = if (config.progressHeightDp > 0f) {
                dpToPx(config.progressHeightDp).roundToInt().coerceAtLeast(2)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            root.layoutParams = rootLp
        }

        bar.layoutParams = bar.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        bar.setTrackColor(config.progressTrackColor)
        bar.setProgressColor(config.progressFillColor)
        bar.setCornerRadiusPx(dpToPx(0f))
    }

    /**
     * 更新显示进度。
     *
     * 与传统“每次都 cancel + restart animator”不同，这里采用“目标值追赶”：
     * - `targetProgress` 始终保存最新目标值
     * - `currentProgress` 按帧持续逼近目标
     *
     * 这样在 300ms 一次更新、甚至一次直接 +20 的场景下，也不会出现反复起步的顿挫感。
     */
    private fun updateDisplayedProgress(targetFraction: Float, animate: Boolean) {
        val bar = progressView ?: return

        val from = currentProgress.coerceIn(0f, 1f)
        val to = targetFraction.coerceIn(0f, 1f)
        targetProgress = to

        if (!animate || from == to) {
            currentProgress = to
            bar.setProgressFraction(to)
            applyStatusByProgress(to)
            stopProgressSmoothing()
            return
        }

        startProgressSmoothing()
    }

    /** 根据进度同步状态文案与缩略图显示。 */
    private fun applyStatusByProgress(progress: Float) {
        val status = tvStatus
        val thumb = ivThumbnail
        if (status == null || thumb == null) return

        if (progress >= 1f) {
            isCompleted = true
            status.text = getString(R.string.upload_progress_status_done)
            thumb.visibility = View.VISIBLE
        } else {
            isCompleted = false
            status.text = getString(R.string.upload_progress_status_uploading)
            thumb.visibility = View.GONE
        }
    }

    /** close 后若允许继续更新进度，则自动恢复面板可见。 */
    private fun ensurePanelVisibleIfNeeded() {
        if (!isClosed) return

        val root = panelRoot ?: return
        isClosed = false
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f
    }

    /** 启动按帧追赶任务；若已在运行则仅更新时间戳，避免重复 post。 */
    private fun startProgressSmoothing() {
        val bar = progressView ?: return
        if (isProgressSmoothingRunning) return
        isProgressSmoothingRunning = true
        lastProgressFrameTimeMs = 0L
        bar.removeCallbacks(progressFrameRunnable)
        bar.postOnAnimation(progressFrameRunnable)
    }

    /** 停止按帧追赶任务。 */
    private fun stopProgressSmoothing() {
        isProgressSmoothingRunning = false
        lastProgressFrameTimeMs = 0L
        progressView?.removeCallbacks(progressFrameRunnable)
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
