package com.vam.demov

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vam.demov.dialog.DialogTopLiftController
import com.vam.demov.view.FourCornerView

/**
 * DialogTopLiftController 演示页面。
 *
 * 页面结构：
 * - 活动区：铺满父布局宽度、高度为父布局 2/3 的 FrameLayout（clipChildren=true，超出不显示）
 * - 目标 View：FourCornerView，初始位于活动区左下角
 * - Dialog：底部弹出，Dialog 展示后目标 View 随之上移等量高度；Dialog 关闭后自动复位
 *
 * 注意：Controller 独占 setOnShowListener / setOnDismissListener，
 * 外部通过 setOnShowCallback / setOnDismissCallback 监听事件。
 */
class DialogTopLiftDemoActivity : AppCompatActivity() {

    companion object {
        const val TAG = "DialogTopLiftDemoAct"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    private lateinit var tvStatus: TextView
    private lateinit var btnShowDialog: Button
    private lateinit var targetView: FourCornerView

    private val controller = DialogTopLiftController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialog_top_lift_demo)

        tvStatus = findViewById(R.id.tvStatus)
        btnShowDialog = findViewById(R.id.btnShowDialog)
        targetView = findViewById(R.id.vTarget)

        controller.bindTarget(targetView)
        // 通过 Controller 回调更新状态文字，避免直接设置 Dialog 监听导致覆盖
        controller.setOnShowCallback {
            tvStatus.text = getString(R.string.dialog_top_lift_status_shown)
            logI(TAG, "onShowCallback: Dialog 已展示，目标 View 上移中")
        }
        controller.setOnDismissCallback {
            tvStatus.text = getString(R.string.dialog_top_lift_status_dismiss)
            logD(TAG, "onDismissCallback: Dialog 已关闭，目标 View 复位中")
        }

        tvStatus.text = getString(R.string.dialog_top_lift_status_idle)
        btnShowDialog.setOnClickListener { showLiftDialog() }
    }

    private fun showLiftDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_top_lift_content)
        dialog.setCanceledOnTouchOutside(true)

        // attachDialog 独占 show/dismiss 监听，调用后不再设置任何监听
        controller.attachDialog(dialog)

        dialog.show()
        // show() 后立即调整窗口属性（此时 window 已存在且高度已触发 post 读取）
        setupDialogWindow(dialog)
    }

    private fun setupDialogWindow(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.attributes = window.attributes.apply {
            y = dpToPx(12f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
