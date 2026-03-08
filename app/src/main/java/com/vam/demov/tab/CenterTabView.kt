package com.vam.demov.tab

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView

/**
 * 居中选中 Tab 控件。
 *
 * 特性：
 * - item 水平紧挨排列，宽度由文字内容自适应（wrap_content + padding）
 * - 选中项始终居于控件正中央，切换时整体平移动画使目标 item 移入中心
 * - 选中项文字加粗（paint.isFakeBoldText），取消选中恢复 normal
 * - 选中项文字底部绘制一个黑色圆点作为指示器
 *
 * 字重实现说明：
 * 使用 [android.graphics.Paint.isFakeBoldText] 而非 [android.graphics.Typeface.BOLD]。
 * isFakeBoldText 仅影响绘制，不触发 TextView 的 requestLayout()，
 * 因此切换字重不会产生 layout pass，也不会打断正在进行的平移动画（消除闪烁）。
 *
 * 点击控制：
 * 默认开启 item 点击切换，可通过 [disableItemClick] / [enableItemClick] 动态禁用/恢复。
 * 禁用后程序调用 [selectTab] 仍正常生效，仅屏蔽用户手指点击触发的切换。
 *
 * 使用示例：
 * ```kotlin
 * centerTabView.setItems(listOf("视频", "照片"))
 * centerTabView.setOnTabSelectedListener { index, label -> }
 * centerTabView.selectTab(1, animate = false)
 * centerTabView.disableItemClick()   // 禁止点击
 * centerTabView.enableItemClick()    // 恢复点击
 * ```
 */
class CenterTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    companion object {
        private const val TAG = "CenterTabView"
    }

    // -------------------------------------------------------------------------
    // 配置与状态
    // -------------------------------------------------------------------------

    private var config = CenterTabConfig()

    /** 当前选中的 tab 索引 */
    var selectedIndex: Int = 0
        private set

    /** 所有 tab 文本 item（由 [setItems] 动态创建） */
    private val itemViews = mutableListOf<TextView>()

    /**
     * 每个 item 在 layout 坐标系中的 left 位置（不含 translationX）。
     * 在 onLayout 后更新，供居中计算使用。
     */
    private val itemLefts = mutableListOf<Int>()

    /** 圆点绘制画笔 */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 当前正在运行的切换动画，切换前取消 */
    private var runningAnimator: AnimatorSet? = null

    /** 对外回调：tab 切换时触发 */
    private var onTabSelectedListener: ((index: Int, label: String) -> Unit)? = null

    /** item 点击切换是否开启，默认开启 */
    private var itemClickEnabled: Boolean = true

    // -------------------------------------------------------------------------
    // 初始化
    // -------------------------------------------------------------------------

    init {
        setWillNotDraw(false)
        applyConfig(config)
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 应用配置，刷新所有样式。
     * 在 [setItems] 之前或之后调用均可。
     */
    fun applyConfig(cfg: CenterTabConfig) {
        config = cfg
        dotPaint.color = cfg.dotColor
        refreshItemStyles()
        invalidate()
    }

    /**
     * 设置 tab 文本列表，会清空并重新创建所有子 View。
     *
     * @param labels tab 文本列表，数量 >= 1
     */
    fun setItems(labels: List<String>) {
        runningAnimator?.cancel()
        removeAllViews()
        itemViews.clear()
        itemLefts.clear()

        labels.forEachIndexed { i, label ->
            val tv = createItemView(label, isSelected = (i == selectedIndex))
            itemViews.add(tv)
            addView(tv)
        }

        post { applyTranslation(selectedIndex, animate = false) }
        logD(TAG, "setItems: ${labels.size} items, selectedIndex=$selectedIndex")
    }

    /**
     * 切换到指定索引的 tab。
     *
     * @param index   目标索引
     * @param animate 是否执行切换动画，默认 true
     */
    fun selectTab(index: Int, animate: Boolean = true) {
        if (index < 0 || index >= itemViews.size) {
            logE(TAG, "selectTab: invalid index $index, size=${itemViews.size}")
            return
        }
        if (index == selectedIndex) {
            logD(TAG, "selectTab: already at index $index, skip")
            return
        }

        val prevIndex = selectedIndex
        selectedIndex = index

        // isFakeBoldText 只改绘制，不触发 requestLayout，不会打断动画
        applyBoldState(prevIndex, isBold = false)
        applyBoldState(index, isBold = true)

        // 颜色变更同样用 invalidate() 驱动，不影响 layout
        applyColorState(prevIndex, isSelected = false)
        applyColorState(index, isSelected = true)

        applyTranslation(index, animate = animate)

        onTabSelectedListener?.invoke(index, itemViews[index].text.toString())
        logD(TAG, "selectTab: $prevIndex -> $index")
    }

    /**
     * 设置 tab 切换回调。
     *
     * @param listener (index, label) -> Unit
     */
    fun setOnTabSelectedListener(listener: (index: Int, label: String) -> Unit) {
        onTabSelectedListener = listener
    }

    /**
     * 开启 item 点击切换（默认已开启）。
     * 开启后点击任意 item 可触发 [selectTab]。
     */
    fun enableItemClick() {
        if (itemClickEnabled) {
            logD(TAG, "enableItemClick: already enabled, skip")
            return
        }
        itemClickEnabled = true
        logD(TAG, "enableItemClick: enabled")
    }

    /**
     * 禁止 item 点击切换。
     * 禁止后点击 item 不再触发 [selectTab]，[selectTab] 程序调用仍正常生效。
     */
    fun disableItemClick() {
        if (!itemClickEnabled) {
            logD(TAG, "disableItemClick: already disabled, skip")
            return
        }
        itemClickEnabled = false
        logD(TAG, "disableItemClick: disabled")
    }

    /**
     * 返回当前 item 点击切换是否开启。
     */
    fun isItemClickEnabled(): Boolean = itemClickEnabled

    // -------------------------------------------------------------------------
    // 测量与布局
    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)

        if (itemViews.isEmpty()) {
            setMeasuredDimension(parentWidth, 0)
            return
        }

        // item 宽度自适应（wrap_content）
        // 注意：isFakeBoldText 不影响 measure 宽度，所有 item 用同一套宽度测量结果即可
        val itemWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val itemHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var maxItemHeight = 0
        itemViews.forEach { tv ->
            tv.measure(itemWidthSpec, itemHeightSpec)
            if (tv.measuredHeight > maxItemHeight) maxItemHeight = tv.measuredHeight
        }

        val dotSize = config.dotSizeDp.dpToPx()
        val dotMargin = config.dotMarginTopDp.dpToPx()
        val totalHeight = maxItemHeight + dotMargin.toInt() + dotSize.toInt()

        setMeasuredDimension(parentWidth, totalHeight)
        logD(TAG, "onMeasure: parentWidth=$parentWidth, height=$totalHeight")
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (itemViews.isEmpty()) return

        itemLefts.clear()
        var xOffset = 0
        itemViews.forEach { tv ->
            itemLefts.add(xOffset)
            tv.layout(xOffset, 0, xOffset + tv.measuredWidth, tv.measuredHeight)
            xOffset += tv.measuredWidth
        }

        // 每次 layout 后都同步平移就位（无动画），保证方向正确
        // 因为 isFakeBoldText 不触发 requestLayout，这里不会干扰正在进行的动画
        applyTranslation(selectedIndex, animate = false)
    }

    // -------------------------------------------------------------------------
    // 绘制圆点
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (itemViews.isEmpty()) return

        val dotRadius = config.dotSizeDp.dpToPx() / 2f
        val dotMargin = config.dotMarginTopDp.dpToPx()
        val selectedView = itemViews.getOrNull(selectedIndex) ?: return
        val dotY = selectedView.measuredHeight + dotMargin + dotRadius
        val dotX = width / 2f

        dotPaint.color = config.dotColor
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
    }

    // -------------------------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------------------------

    /**
     * 创建单个 item TextView。
     * 字重通过 [paint.isFakeBoldText] 控制，避免 setTypeface 触发 requestLayout。
     */
    private fun createItemView(label: String, isSelected: Boolean): TextView {
        val padH = config.itemPaddingHorizontalDp.dpToPx().toInt()
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(padH, 0, padH, 0)
            // 初始颜色
            setTextColor(if (isSelected) config.selectedTextColor else config.normalTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.normalTextSizeSp)
            // 初始字重（isFakeBoldText 只影响绘制）
            paint.isFakeBoldText = isSelected
            setOnClickListener {
                if (!itemClickEnabled) return@setOnClickListener
                // 动画执行期间忽略点击，防止连续快速点击触发多次切换
                if (runningAnimator?.isRunning == true) return@setOnClickListener
                val clickedIndex = itemViews.indexOf(this)
                if (clickedIndex >= 0) selectTab(clickedIndex)
            }
        }
    }

    /**
     * 仅切换字重（isFakeBoldText），不触发 requestLayout，不中断动画。
     */
    private fun applyBoldState(index: Int, isBold: Boolean) {
        itemViews.getOrNull(index)?.let { tv ->
            tv.paint.isFakeBoldText = isBold
            tv.invalidate()
        }
    }

    /**
     * 仅切换颜色（setTextColor），不触发 requestLayout，不中断动画。
     */
    private fun applyColorState(index: Int, isSelected: Boolean) {
        itemViews.getOrNull(index)?.setTextColor(
            if (isSelected) config.selectedTextColor else config.normalTextColor
        )
    }

    /** 刷新所有 item 的样式（applyConfig 后调用）。 */
    private fun refreshItemStyles() {
        itemViews.forEachIndexed { i, tv ->
            val sel = (i == selectedIndex)
            tv.paint.isFakeBoldText = sel
            tv.setTextColor(if (sel) config.selectedTextColor else config.normalTextColor)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.normalTextSizeSp)
            tv.invalidate()
        }
    }

    /**
     * 计算并应用整体 translationX，使 [targetIndex] 对应的 item 居中。
     *
     * 中心对齐：
     * - item[i] 中心 X（不含平移）= itemLefts[i] + measuredWidth / 2
     * - 控件中心 X = width / 2
     * - translationX = width/2 - itemCenter
     */
    private fun applyTranslation(targetIndex: Int, animate: Boolean) {
        if (itemViews.isEmpty() || itemLefts.size <= targetIndex) return

        val targetView = itemViews[targetIndex]
        val itemCenter = itemLefts[targetIndex] + targetView.measuredWidth / 2f
        val targetTx = width / 2f - itemCenter

        if (!animate) {
            setChildrenTranslationX(targetTx)
            invalidate()
            return
        }

        runningAnimator?.cancel()
        val currentTx = itemViews.firstOrNull()?.translationX ?: targetTx

        val txAnim = ValueAnimator.ofFloat(currentTx, targetTx).apply {
            duration = config.switchAnimDurationMs
            addUpdateListener { anim ->
                setChildrenTranslationX(anim.animatedValue as Float)
                invalidate()
            }
        }

        val set = AnimatorSet()
        set.play(txAnim)
        runningAnimator = set
        set.start()

        logD(TAG, "applyTranslation: index=$targetIndex, tx=$currentTx -> $targetTx")
    }

    /** 将所有子 item 的 translationX 设置为同一值。 */
    private fun setChildrenTranslationX(tx: Float) {
        itemViews.forEach { it.translationX = tx }
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private fun Float.dpToPx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)
}
