package com.vam.demov.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Camera2 预览引擎。
 *
 * 职责：
 *   - 管理 CameraDevice 的打开 / 关闭生命周期
 *   - 建立 CameraCaptureSession 并持续输出预览帧到 TextureView
 *   - 支持前后摄切换（[toggleCamera]）
 *   - 支持闪光灯开关（[setFlash]）
 *
 * 调用规范：
 *   - TextureView.isAvailable == true 之后调用 [open]
 *   - Activity/Fragment onPause 时调用 [close]
 *   - Activity/Fragment onResume 且 Surface 就绪时再调用 [open]
 */
class CameraEngine(private val context: Context) {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "CameraEngine"
    }

    private fun logD(content: String) = Log.d(TAG, content)
    private fun logI(content: String) = Log.i(TAG, content)
    private fun logE(content: String) = Log.e(TAG, content)

    // -------------------------------------------------------------------------
    // 状态
    // -------------------------------------------------------------------------

    /** 当前使用的镜头朝向，默认前摄 */
    private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT

    /** 是否开启闪光灯 */
    private var flashOn = false

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /** 后台线程处理 Camera2 回调，避免阻塞主线程 */
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 打开相机并开始向 [textureView] 输出预览。
     * 需在 TextureView Surface 就绪后调用。
     */
    @SuppressLint("MissingPermission")
    fun open(textureView: TextureView) {
        startBackgroundThread()
        val cameraId = getCameraId(lensFacing) ?: run {
            logE("找不到目标镜头 cameraId，lensFacing=$lensFacing")
            return
        }
        logI("open: cameraId=$cameraId, lensFacing=$lensFacing")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                logI("CameraDevice onOpened")
                startPreview(camera, textureView)
            }

            override fun onDisconnected(camera: CameraDevice) {
                logI("CameraDevice onDisconnected")
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                logE("CameraDevice onError: error=$error")
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    /** 关闭相机，释放所有资源 */
    fun close() {
        logI("close")
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }

    /**
     * 前后摄切换。
     * @param textureView 当前用于显示预览的 TextureView
     */
    fun toggleCamera(textureView: TextureView) {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        logI("toggleCamera: lensFacing -> $lensFacing")
        close()
        open(textureView)
    }

    /**
     * 设置闪光灯开关，立即生效（仅后摄支持）。
     */
    fun setFlash(on: Boolean) {
        flashOn = on
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        logI("setFlash: on=$on")
    }

    // -------------------------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------------------------

    private fun startPreview(camera: CameraDevice, textureView: TextureView) {
        val texture = textureView.surfaceTexture ?: run {
            logE("startPreview: SurfaceTexture is null")
            return
        }

        // 获取相机支持的预览尺寸，选取合适的 4:3 分辨率
        val previewSize = getOptimalPreviewSize(camera.id)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)

        val surface = Surface(texture)
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(
                CaptureRequest.FLASH_MODE,
                if (flashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
        }
        previewRequestBuilder = builder

        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    logI("CaptureSession configured, preview started, size=${previewSize.width}x${previewSize.height}")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logE("CaptureSession onConfigureFailed")
                }
            },
            backgroundHandler
        )
    }

    /**
     * 从相机支持的输出尺寸中选出最接近 4:3 且不超过 1920x1440 的最大分辨率。
     */
    private fun getOptimalPreviewSize(cameraId: String): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 960)

        val target = sizes
            .filter { it.width <= 1920 && it.height <= 1440 }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 960)

        logI("getOptimalPreviewSize: selected ${target.width}x${target.height}")
        return target
    }

    /**
     * 根据 [facing] 获取对应 cameraId。
     */
    private fun getCameraId(facing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }
}
