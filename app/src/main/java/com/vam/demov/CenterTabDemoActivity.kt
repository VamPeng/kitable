package com.vam.demov

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vam.demov.tab.CenterTabConfig
import com.vam.demov.tab.CenterTabView

/**
 * CenterTabView 演示页面。
 *
 * 展示居中选中 Tab 的平移动画、字重切换与圆点指示效果。
 */
class CenterTabDemoActivity : AppCompatActivity() {

    private lateinit var centerTabView: CenterTabView
    private lateinit var tvStatus: TextView

    private val tabLabels = listOf("视频", "照片")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_center_tab_demo)

        centerTabView = findViewById(R.id.centerTabView)
        tvStatus = findViewById(R.id.tvStatus)

        // 初始化 Tab 配置
        centerTabView.applyConfig(
            CenterTabConfig(
                normalTextSizeSp = 14f,
                selectedTextSizeSp = 14f,
                normalTextColor = 0xFF999999.toInt(),
                selectedTextColor = 0xFF000000.toInt(),
                dotColor = 0xFF000000.toInt(),
                dotSizeDp = 6f,
                dotMarginTopDp = 4f,
                switchAnimDurationMs = 300L,
                itemPaddingHorizontalDp = 6f,
            )
        )

        // 设置 tab 数据，默认选中索引 1（"照片"），不执行动画
        centerTabView.setItems(tabLabels)
        centerTabView.selectTab(1, animate = false)

        // 切换回调
        centerTabView.setOnTabSelectedListener { index, label ->
            updateStatus(index, label)
        }

        // 手动点击 tab item 支持（通过按钮模拟，接入方可直接在 item 上设置点击）
        setupButtons()

        updateStatus(1, tabLabels[1])
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPrev).setOnClickListener {
            val prev = (centerTabView.selectedIndex - 1 + tabLabels.size) % tabLabels.size
            centerTabView.selectTab(prev)
        }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            val next = (centerTabView.selectedIndex + 1) % tabLabels.size
            centerTabView.selectTab(next)
        }
        findViewById<Button>(R.id.btnSelect0).setOnClickListener { centerTabView.selectTab(0) }
        findViewById<Button>(R.id.btnSelect1).setOnClickListener { centerTabView.selectTab(1) }
    }

    private fun updateStatus(index: Int, label: String) {
        tvStatus.text = "当前选中：[$index] $label"
    }
}
