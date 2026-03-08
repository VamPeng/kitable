package com.vam.demov.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.vam.demov.R

/**
 * 四角数字调试 View。
 *
 * 蓝色背景矩形，四个角各绘制一个数字（从左上角顺时针：1、2、3、4），
 * 用于直观验证 TiltViewController 旋转和平移效果。
 *
 * 支持通过 XML 属性设置宽高：
 * - `app:fcv_width`  — 自定义宽度（dimension）
 * - `app:fcv_height` — 自定义高度（dimension）
 *
 * 如未设置，MeasureSpec 决定大小（XML 中 layout_width/height 仍生效）。
 */
class FourCornerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val TAG = "FourCornerView"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 自定义属性解析
    // -------------------------------------------------------------------------

    /** 从 XML 属性读取的期望宽度（px），-1 表示未设置 */
    private var desiredWidth: Int = -1

    /** 从 XML 属性读取的期望高度（px），-1 表示未设置 */
    private var desiredHeight: Int = -1

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.FourCornerView, defStyleAttr, 0)
            try {
                val w = ta.getDimensionPixelSize(R.styleable.FourCornerView_fcv_width, -1)
                val h = ta.getDimensionPixelSize(R.styleable.FourCornerView_fcv_height, -1)
                if (w > 0) desiredWidth = w
                if (h > 0) desiredHeight = h
                logD(TAG, "init: desiredWidth=$desiredWidth desiredHeight=$desiredHeight")
            } finally {
                ta.recycle()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 画笔
    // -------------------------------------------------------------------------

    /** 背景填充画笔 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1976D2")   // 蓝色背景
    }

    /** 数字文字画笔 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** 角落内边距（px），数字距角落的偏移 */
    private val cornerPadding: Float = resources.displayMetrics.density * 10f

    // -------------------------------------------------------------------------
    // Measure
    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 未设置自定义尺寸时，保持系统默认测量行为。
        if (desiredWidth <= 0 && desiredHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val measuredWidth = if (desiredWidth > 0) {
            resolveDesiredSizeAllowOverflow(desiredWidth, widthMeasureSpec)
        } else {
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        }

        val measuredHeight = if (desiredHeight > 0) {
            resolveDesiredSizeAllowOverflow(desiredHeight, heightMeasureSpec)
        } else {
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    /**
     * 允许自定义尺寸在 AT_MOST/UNSPECIFIED 下保持原值，不被父布局可用空间截断。
     * 仅在 EXACTLY 模式下遵循父布局强约束。
     */
    private fun resolveDesiredSizeAllowOverflow(desired: Int, measureSpec: Int): Int {
        return when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(measureSpec)
            MeasureSpec.AT_MOST, MeasureSpec.UNSPECIFIED -> desired
            else -> desired
        }
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 字号 = 高度的 28%，保证在不同尺寸下都清晰可见
        textPaint.textSize = h * 0.28f
        logD(TAG, "onSizeChanged: ${w}x${h}, textSize=${textPaint.textSize}")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制蓝色背景
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 各角数字的 (x, y) 基准点（Paint.Align.CENTER 水平居中，y 为 baseline）
        // 文字基线偏移：从顶部 / 底部各退 cornerPadding + textSize
        val textSize = textPaint.textSize
        val fm = textPaint.fontMetrics
        // 上半部分 baseline：顶部 padding + ascent 高度
        val topBaseline = cornerPadding - fm.top           // fm.top 为负值
        // 下半部分 baseline：底部 padding + 字符高度内推
        val bottomBaseline = h - cornerPadding - fm.bottom // fm.bottom 为正值

        // 左右中心 x
        val leftX = cornerPadding + textSize / 2f
        val rightX = w - cornerPadding - textSize / 2f

        // 1：左上角
        canvas.drawText("1", leftX, topBaseline, textPaint)
        // 2：右上角
        canvas.drawText("2", rightX, topBaseline, textPaint)
        // 3：右下角
        canvas.drawText("3", rightX, bottomBaseline, textPaint)
        // 4：左下角
        canvas.drawText("4", leftX, bottomBaseline, textPaint)
    }
}
