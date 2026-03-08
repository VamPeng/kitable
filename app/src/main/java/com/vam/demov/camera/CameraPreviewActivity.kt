package com.vam.demov.camera

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.vam.demov.R
import com.vam.demov.dialog.DialogTopLiftController
import com.vam.demov.tab.CenterTabConfig
import com.vam.demov.tab.CenterTabView
import com.vam.demov.tilt.TiltViewController
import com.vam.demov.upload.UploadProgressConfig
import com.vam.demov.upload.UploadProgressFragment
import com.vam.demov.view.FourCornerView

/**
 * 水印相机预览界面
 *
 * 当前阶段：Camera2 实时预览 + TopBar + BottomBar（Tab / 拍摄 / 弹框按钮）
 * 后续阶段：水印叠加、拍照、录视频、工具集成
 *
 * 权限：CAMERA + RECORD_AUDIO + 存储（按 API 版本区分）
 */
class CameraPreviewActivity : AppCompatActivity() {

    // ---- 日志 ----
    companion object {
        private const val TAG = "CameraPreviewActivity"
    }

    private fun logD(tag: String = TAG, content: String) = Log.d(tag, content)
    private fun logI(tag: String = TAG, content: String) = Log.i(tag, content)
    private fun logE(tag: String = TAG, content: String) = Log.e(tag, content)

    // ---- 视图 ----
    private lateinit var textureView: TextureView
    private lateinit var btnFlash: ImageButton

    // ---- Camera ----
    private val cameraEngine by lazy { CameraEngine(this) }

    // ---- TiltViewController ----
    private val tiltViewController by lazy { TiltViewController(lifecycleOwner = this) }

    // ---- DialogTopLiftController ----
    private val liftController = DialogTopLiftController()
    private var galleryDialog: Dialog? = null

    // ---- UploadProgressFragment ----
    private lateinit var uploadFragment: UploadProgressFragment
    private val progressHandler = Handler(Looper.getMainLooper())
    private var simProgress = 0

    /** 权限通过后 Surface 可能还没就绪，用此标记待开启 */
    private var permissionGranted = false

    // ---- 状态 ----
    private var isFlashOn = false

    // ---- 权限申请 ----

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            logI(content = "所有权限已授予")
            permissionGranted = true
            openCameraIfReady()
        } else {
            val denied = results.filterValues { !it }.keys.joinToString()
            logE(content = "权限被拒绝: $denied")
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---- 生命周期 ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 白色状态栏 + 深色图标
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        setContentView(R.layout.activity_camera_preview)

        textureView = findViewById(R.id.textureView)
        btnFlash = findViewById(R.id.btnFlash)

        initTopBar()
        initBottomBar()
        initTiltController()
        initLiftController()
        initUploadProgress()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来时若 Surface 已就绪则重新开启相机
        if (permissionGranted && textureView.isAvailable) {
            cameraEngine.open(textureView)
        }
    }

    override fun onPause() {
        super.onPause()
        cameraEngine.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        liftController.release()
        galleryDialog = null
        progressHandler.removeCallbacksAndMessages(null)
    }

    // ---- 权限 ----

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return list.toTypedArray()
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionGranted = true
            openCameraIfReady()
        } else {
            logI(content = "申请权限: ${missing.joinToString()}")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- 相机启动 ----

    /**
     * 权限与 Surface 都就绪后才真正打开相机。
     * TextureView 的 Surface 可能在权限回调之后才可用，通过 SurfaceTextureListener 等待。
     */
    private fun openCameraIfReady() {
        if (!permissionGranted) return

        if (textureView.isAvailable) {
            cameraEngine.open(textureView)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    logI(content = "SurfaceTexture available, opening camera")
                    cameraEngine.open(textureView)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    // ---- DialogTopLiftController ----

    /**
     * 绑定 FourCornerView，构建相册 Dialog 并附加到 Controller。
     * Dialog 弹出时暂停 TiltViewController 避免 translationY 冲突，关闭后恢复。
     */
    private fun initLiftController() {
        val fourCornerView = findViewById<FourCornerView>(R.id.fourCornerView)

        // bindTarget 内部 post 一帧，自动等待 layout 完成后记录初始坐标
        liftController.bindTarget(fourCornerView)

        val dialog = buildGalleryDialog()
        galleryDialog = dialog

        liftController
            .setOnShowCallback {
                tiltViewController.stopListening()
                logD(content = "Gallery dialog shown, tilt paused")
            }
            .setOnDismissCallback {
                tiltViewController.startListening()
                logD(content = "Gallery dialog dismissed, tilt resumed")
            }
            .attachDialog(dialog)
    }

    private fun buildGalleryDialog(): Dialog {
        // 调整此比例控制弹框高度（占屏幕高度的比例，0.0 ~ 1.0）
        val heightRatio = 0.5f

        val screenHeight = resources.displayMetrics.heightPixels
        val dialogHeight = (screenHeight * heightRatio).toInt()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_camera_gallery)
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            // 高度直接传给 window，不依赖内容 wrap_content
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dialogHeight)
            setBackgroundDrawableResource(R.drawable.bg_dialog_panel)
        }
        return dialog
    }

    // ---- UploadProgressFragment ----

    /**
     * 挂载 UploadProgressFragment 到 uploadProgressContainer，启动循环进度模拟。
     * 循环规则：每 500ms +10 进度（5s 跑完），完成后等待 10s 重新开始。
     */
    private fun initUploadProgress() {
        uploadFragment = UploadProgressFragment.newInstance()
        uploadFragment.applyConfig(
            UploadProgressConfig(
                progressWidthDp = 0f,   // match_parent，跟随容器宽度
                progressHeightDp = 30f,
            )
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.uploadProgressContainer, uploadFragment)
            .commitNow()

        startProgressCycle()
    }

    /** 从 0 开始新一轮进度模拟 */
    private fun startProgressCycle() {
        simProgress = 0
        uploadFragment.resetAndShow(0)
        scheduleProgressStep()
        logD(content = "Upload progress cycle started")
    }

    /** 每 500ms 推进一步（+10），到 100 后等 10s 重新开始 */
    private fun scheduleProgressStep() {
        progressHandler.postDelayed({
            simProgress += 10
            if (simProgress >= 100) {
                uploadFragment.markUploadCompleted()
                logD(content = "Upload completed, next cycle in 10s")
                progressHandler.postDelayed({ startProgressCycle() }, 10_000L)
            } else {
                uploadFragment.updateProgress(simProgress)
                scheduleProgressStep()
            }
        }, 500L)
    }

    // ---- TiltViewController ----

    /**
     * 绑定 FourCornerView，活动区域取 cameraPreviewContainer 的屏幕坐标 RectF。
     * doOnLayout 确保布局完成后坐标有效。
     */
    private fun initTiltController() {
        val fourCornerView = findViewById<FourCornerView>(R.id.fourCornerView)
        val previewContainer = findViewById<FrameLayout>(R.id.cameraPreviewContainer)

        previewContainer.doOnLayout {
            val loc = IntArray(2)
            previewContainer.getLocationOnScreen(loc)
            val boundary = RectF(
                loc[0].toFloat(),
                loc[1].toFloat(),
                (loc[0] + previewContainer.width).toFloat(),
                (loc[1] + previewContainer.height).toFloat()
            )
            logI(content = "TiltViewController boundary: $boundary")
            tiltViewController.bind(fourCornerView, boundary)
        }
    }

    // ---- TopBar ----

    private fun initTopBar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            cameraEngine.setFlash(isFlashOn)
            btnFlash.setImageResource(
                if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
            logD(content = "Flash: $isFlashOn")
        }

        findViewById<ImageButton>(R.id.btnFlipCamera).setOnClickListener {
            isFlashOn = false
            btnFlash.setImageResource(R.drawable.ic_flash_off)
            cameraEngine.toggleCamera(textureView)
            logD(content = "Camera flipped")
        }
    }

    // ---- BottomBar ----

    private fun initBottomBar() {
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener {
            logD(content = "btnCapture clicked")
            // TODO: 拍照
        }

        // 弹框/相册按钮：弹出相册 Dialog，FourCornerView 随之上移
        findViewById<ImageButton>(R.id.btnGallery).setOnClickListener {
            logD(content = "btnGallery clicked, showing gallery dialog")
            galleryDialog?.show()
        }

        val tabLabels = listOf("视频", "拍照")
        val centerTabView = findViewById<CenterTabView>(R.id.centerTabView)
        centerTabView.applyConfig(
            CenterTabConfig(
                normalTextColor = 0xFF999999.toInt(),
                selectedTextColor = 0xFF212121.toInt(),
                dotColor = 0xFF212121.toInt(),
                itemPaddingHorizontalDp = 12f,
            )
        )
        centerTabView.setItems(tabLabels)
        centerTabView.selectTab(1, animate = false)
        centerTabView.setOnTabSelectedListener { index, label ->
            logD(content = "Tab: [$index] $label")
        }

    }
}
