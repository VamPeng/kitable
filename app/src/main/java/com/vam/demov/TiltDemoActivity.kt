package com.vam.demov

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vam.demov.tilt.TiltViewController
import com.vam.demov.view.FourCornerView

/**
 * TiltViewController 调试页面。
 *
 * 页面底部展示 boundary 活动区域（蓝色描边矩形），
 * 内部放置 [FourCornerView] 作为目标 View，四角数字 1-4 便于直观验证旋转和对齐效果。
 */
class TiltDemoActivity : AppCompatActivity() {

    private lateinit var tiltController: TiltViewController
    private lateinit var btnSensorToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tilt_demo)

        val tvAngle = findViewById<TextView>(R.id.tvAngle)
        val tvState = findViewById<TextView>(R.id.tvState)
        btnSensorToggle = findViewById(R.id.btnSensorToggle)
        val targetView = findViewById<FourCornerView>(R.id.targetView)

        // 构造 TiltViewController，传入 LifecycleOwner，生命周期自动托管
        tiltController = TiltViewController(lifecycleOwner = this)

        // 监听 Roll 角实时变化，更新角度显示
        tiltController.onRollChanged = { roll ->
            tvAngle.text = "Roll: ${"%.1f".format(roll)}°"
        }

        // 监听区间切换，更新状态标签
        tiltController.onStateChanged = { stateName, _ ->
            val desc = when (stateName) {
                "D" -> "State: D（默认竖直）"
                "A" -> "State: A（左倾 90°）"
                "B" -> "State: B（右倾 90°）"
                "C" -> "State: C（倒置 180°）"
                else -> "State: $stateName"
            }
            tvState.text = desc
        }

        // 绑定目标 View，boundary 自动取父布局（boundaryArea）可见区域
        tiltController.bind(targetView)

        btnSensorToggle.setOnClickListener {
            if (tiltController.isListeningEnabled()) {
                tiltController.stopListening()
            } else {
                tiltController.startListening()
            }
            updateListenerButtonText()
        }

        updateListenerButtonText()
    }

    override fun onResume() {
        super.onResume()
        updateListenerButtonText()
    }

    private fun updateListenerButtonText() {
        btnSensorToggle.text = if (tiltController.isListeningEnabled()) {
            if (tiltController.isListeningActive()) "监听：开启（点击停止）" else "监听：开启（注册中）"
        } else {
            "监听：停止（点击开启）"
        }
    }
}
