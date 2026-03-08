package com.vam.demov.tab

/**
 * CenterTabView 配置数据类。
 *
 * @param normalTextSizeSp     未选中文本字号（sp），默认 14sp
 * @param selectedTextSizeSp   选中文本字号（sp），默认 14sp（字重通过 bold 体现，字号可与 normal 相同）
 * @param normalTextColor      未选中文本颜色，默认灰色 #999999
 * @param selectedTextColor    选中文本颜色，默认黑色 #000000
 * @param dotColor             选中指示点颜色，默认黑色 #000000
 * @param dotSizeDp            圆点直径（dp），默认 6dp
 * @param dotMarginTopDp       圆点与文本的间距（dp），默认 4dp
 * @param switchAnimDurationMs tab 切换动画时长（ms），默认 300ms
 * @param itemPaddingHorizontalDp 每个 item 的水平内边距（dp），决定字间视觉间隔，默认 16dp
 */
data class CenterTabConfig(
    val normalTextSizeSp: Float = 14f,
    val selectedTextSizeSp: Float = 14f,
    val normalTextColor: Int = 0xFF999999.toInt(),
    val selectedTextColor: Int = 0xFF000000.toInt(),
    val dotColor: Int = 0xFF000000.toInt(),
    val dotSizeDp: Float = 6f,
    val dotMarginTopDp: Float = 4f,
    val switchAnimDurationMs: Long = 300L,
    val itemPaddingHorizontalDp: Float = 16f,
)
