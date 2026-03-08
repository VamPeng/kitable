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
import android.view.View
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
import com.vam.demov.permission.PermissionTipConfig
import com.vam.demov.permission.PermissionTipView
import com.vam.demov.tab.CenterTabConfig
import com.vam.demov.tab.CenterTabView
import com.vam.demov.tilt.TiltViewController
import com.vam.demov.upload.UploadProgressConfig
import com.vam.demov.upload.UploadProgressFragment
import com.vam.demov.view.FourCornerView
import kotlin.random.Random

/**
 * 水印相机预览界面
 *
 * 当前阶段：Camera2 实时预览 + TopBar + BottomBar（Tab / 拍摄 / 弹框按钮）
 * 后续阶段：水印叠加、拍照、录视频、工具集成
 *
 * 权限：
 *   - 相机权限（CAMERA + RECORD_AUDIO + 存储）：必须，缺失则 finish()
 *   - 位置权限（ACCESS_FINE_LOCATION）：可选，缺失时由 PermissionTipView 提示
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

    // ---- PermissionTipView & 气泡三角形 ----
    private lateinit var permissionTipView: PermissionTipView
    private lateinit var triangleTip: View

    /** 相机权限通过后 Surface 可能还没就绪，用此标记待开启 */
    private var permissionGranted = false

    // ---- 状态 ----
    private var isFlashOn = false

    // ---- 相机权限申请 ----

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            logI(content = "相机权限已全部授予")
            permissionGranted = true
            openCameraIfReady()
        } else {
            val denied = results.filterValues { !it }.keys.joinToString()
            logE(content = "相机权限被拒绝: $denied")
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---- 位置权限申请 ----

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            logI(content = "位置权限已授予")
            permissionTipView.hideTip()
            triangleTip.visibility = View.GONE
        } else {
            logE(content = "位置权限被拒绝")
            permissionTipView.showTip(getString(R.string.permission_tip_location_denied))
            // 三角形已可见，无需重复设置
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
        initPermissionTip()
        checkAndRequestCameraPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来时若 Surface 已就绪则重新开启相机
        if (permissionGranted && textureView.isAvailable) {
            cameraEngine.open(textureView)
        }
        // 从系统设置返回时重新检查位置权限状态
        checkLocationPermission()
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

    // ---- 相机权限 ----

    private fun requiredCameraPermissions(): Array<String> {
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

    private fun checkAndRequestCameraPermissions() {
        val missing = requiredCameraPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionGranted = true
            openCameraIfReady()
        } else {
            logI(content = "申请相机权限: ${missing.joinToString()}")
            cameraPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- 位置权限 ----

    /**
     * 检查位置权限状态，并同步更新 PermissionTipView + 气泡三角形的显示/隐藏。
     * 在 onCreate 和 onResume 中调用，确保从设置页返回后能及时刷新。
     */
    private fun checkLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            permissionTipView.hideTip(animate = false)
            triangleTip.visibility = View.GONE
            logD(content = "位置权限已授予，隐藏提示条")
        } else {
            permissionTipView.showTip(getString(R.string.permission_tip_location), animate = false)
            triangleTip.visibility = View.VISIBLE
            logD(content = "位置权限未授予，显示提示条")
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

        liftController.setOnShowCallback {
            tiltViewController.stopListening()
            logD(content = "Gallery dialog shown, tilt paused")
        }.setOnDismissCallback {
            tiltViewController.startListening()
            logD(content = "Gallery dialog dismissed, tilt resumed")
        }.attachDialog(dialog)
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
     * 挂载 UploadProgressFragment 到 uploadProgressContainer。
     * 初始状态隐藏，待实际上传任务触发时再显示。
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
            .replace(R.id.uploadProgressContainer, uploadFragment).commitNow()

        // 先隐藏，待实际上传任务触发时通过 resetAndShow() 显示
        findViewById<View>(R.id.uploadProgressContainer).visibility = View.GONE
        logD(content = "Upload progress initialized, hidden by default")
    }

    /** 从 0 开始新一轮进度模拟 */
    private fun startProgressCycle() {
        simProgress = 0
        findViewById<View>(R.id.uploadProgressContainer).visibility = View.VISIBLE
        uploadFragment.resetAndShow(0)
        scheduleProgressStep()
        logD(content = "Upload progress cycle started")
    }

    /** 每 300ms 随机推进一步（+1..10），到 100 后隐藏上传条 */
    private fun scheduleProgressStep() {
        progressHandler.postDelayed({
            simProgress += Random.nextInt(1, 11)
            if (simProgress >= 100) {
                uploadFragment.markUploadCompleted()
                logD(content = "Upload completed, next cycle in 10s")

                progressHandler.postDelayed({
                    findViewById<View>(R.id.uploadProgressContainer).visibility = View.GONE
                }, 1_000L)
            } else {
                uploadFragment.updateProgress(simProgress)
                scheduleProgressStep()
            }
        }, 300L)
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
            // FourCornerView 的 marginBottom = 12dp，boundary.bottom 同步上移相同距离，
            // 确保 STATE_D / STATE_B 的对齐目标与 View 布局初始位置一致。
            val marginBottomPx = (16f * resources.displayMetrics.density)
            val boundary = RectF(
                loc[0].toFloat(),
                loc[1].toFloat(),
                (loc[0] + previewContainer.width).toFloat(),
                (loc[1] + previewContainer.height).toFloat() - marginBottomPx
            )
            logI(content = "TiltViewController boundary: $boundary")
            tiltViewController.bind(fourCornerView, boundary)
        }
    }

    // ---- PermissionTipView ----

    /**
     * 初始化位置权限提示条：绑定 View、设置蓝色样式、注册"去授权"回调。
     * 实际显示/隐藏由 [checkLocationPermission] 驱动（onCreate + onResume）。
     */
    private fun initPermissionTip() {
        permissionTipView = findViewById(R.id.permissionTipView)
        triangleTip = findViewById(R.id.triangleTip)
        permissionTipView.applyConfig(
            PermissionTipConfig(
                backgroundColor = 0xFF1E88E5.toInt(),
                textColor = 0xFFFFFFFF.toInt(),
                actionText = getString(R.string.permission_tip_action),
                actionTextColor = 0xFF1E88E5.toInt(),
                animDurationMs = 250L,
            )
        )
        // 点击"去授权"：发起位置权限申请
        permissionTipView.setOnActionClickListener {
            logD(content = "PermissionTip action clicked, requesting location permission")
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // 初始检查（onResume 也会再次检查）
        checkLocationPermission()
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
            startProgressCycle()
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
