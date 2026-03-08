package com.vam.demov.upload

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.vam.demov.R
import kotlin.math.min

/**
 * 自绘水平上传进度条（不依赖系统 ProgressBar）。
 *
 * 设计目标：
 * - 仅负责“底层进度绘制”，不承载文案/按钮交互。
 * - 以 0f..1f 浮点进度驱动绘制，便于外层控制动画。
 */
class UploadProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    companion object {
        const val TAG = "UploadProgressBarView"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 绘制数据
    // -------------------------------------------------------------------------

    private val trackRect = RectF()
    private val progressRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D6DEE8")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E88E5")
    }

    private var progressFraction: Float = 0f
    private var cornerRadiusPx: Float = dpToPx(6f)

    // -------------------------------------------------------------------------
    // 初始化：读取 XML 自定义属性
    // -------------------------------------------------------------------------

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.UploadProgressBarView, defStyleAttr, 0)
            try {
                trackPaint.color = ta.getColor(
                    R.styleable.UploadProgressBarView_upv_trackColor,
                    trackPaint.color
                )
                progressPaint.color = ta.getColor(
                    R.styleable.UploadProgressBarView_upv_progressColor,
                    progressPaint.color
                )
                cornerRadiusPx = ta.getDimension(
                    R.styleable.UploadProgressBarView_upv_cornerRadius,
                    cornerRadiusPx
                )
            } finally {
                ta.recycle()
            }
        }
        logD(TAG, "init: track=${trackPaint.color}, progress=${progressPaint.color}, radius=$cornerRadiusPx")
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /** 设置 0f..1f 的进度值。 */
    fun setProgressFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == progressFraction) return
        progressFraction = clamped
        invalidate()
    }

    /** 获取当前 0f..1f 的进度值。 */
    fun getProgressFraction(): Float = progressFraction

    /** 设置底色。 */
    fun setTrackColor(color: Int) {
        if (trackPaint.color == color) return
        trackPaint.color = color
        invalidate()
    }

    /** 设置进度色。 */
    fun setProgressColor(color: Int) {
        if (progressPaint.color == color) return
        progressPaint.color = color
        invalidate()
    }

    /** 设置圆角半径（px）。 */
    fun setCornerRadiusPx(radius: Float) {
        val value = radius.coerceAtLeast(0f)
        if (value == cornerRadiusPx) return
        cornerRadiusPx = value
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Measure / Draw
    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultWidth = dpToPx(220f).toInt()
        val defaultHeight = dpToPx(12f).toInt()
        val width = resolveSize(defaultWidth, widthMeasureSpec)
        val height = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            logE(TAG, "onDraw: 无效尺寸 width=$w height=$h")
            return
        }

        // 圆角最大不超过高度一半，避免出现异常弧形。
        val radius = min(cornerRadiusPx, h / 2f)

        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // 前景进度宽度 = 总宽度 * progressFraction。
        val progressWidth = w * progressFraction
        if (progressWidth > 0f) {
            progressRect.set(0f, 0f, progressWidth, h)
            canvas.drawRoundRect(progressRect, radius, radius, progressPaint)
        }
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
