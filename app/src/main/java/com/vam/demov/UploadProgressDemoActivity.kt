package com.vam.demov

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.vam.demov.upload.UploadProgressConfig
import com.vam.demov.upload.UploadProgressFragment
import kotlin.math.min
import kotlin.random.Random

/**
 * UploadProgressFragment 调试页面。
 */
class UploadProgressDemoActivity : AppCompatActivity() {

    companion object {
        const val TAG = "UploadProgressDemoAct"
        private const val FRAGMENT_TAG = "upload_progress_fragment"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    private lateinit var tvDemoState: TextView
    private lateinit var btnStart: Button
    private lateinit var btnComplete: Button
    private lateinit var btnClose: Button
    private lateinit var swCloseAnim: SwitchCompat

    private val handler = Handler(Looper.getMainLooper())
    private var simulatedProgress = 0
    private var uploadTicker: Runnable? = null

    private lateinit var uploadFragment: UploadProgressFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_progress_demo)

        tvDemoState = findViewById(R.id.tvUploadDemoState)
        btnStart = findViewById(R.id.btnUploadStart)
        btnComplete = findViewById(R.id.btnUploadComplete)
        btnClose = findViewById(R.id.btnUploadClose)
        swCloseAnim = findViewById(R.id.swUploadCloseAnim)

        setupFragment(savedInstanceState)
        bindActions()

        tvDemoState.text = getString(R.string.upload_progress_demo_state_idle)
    }

    override fun onDestroy() {
        stopSimulation()
        super.onDestroy()
    }

    private fun setupFragment(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val fragment = UploadProgressFragment.newInstance().apply {
                applyConfig(
                    UploadProgressConfig(
                        progressWidthDp = 0f,
                        progressHeightDp = 44f,
                        progressTrackColor = 0xFFDCE4ED.toInt(),
                        progressFillColor = 0xFF2E7DFF.toInt(),
                        progressCornerRadiusDp = 8f,
                        progressAnimationEnabled = true,
                        closeAnimationEnabled = true
                    )
                )
                setThumbnailRes(R.drawable.bg_upload_thumb_placeholder)
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.uploadProgressFragmentContainer, fragment, FRAGMENT_TAG)
                .commitNow()
        }

        val fragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
        if (fragment is UploadProgressFragment) {
            uploadFragment = fragment
        } else {
            logE(TAG, "setupFragment: UploadProgressFragment 未找到")
            finish()
            return
        }

        uploadFragment.setOnCloseClickListener {
            stopSimulation()
            tvDemoState.text = getString(R.string.upload_progress_demo_state_closed)
        }

        uploadFragment.setOnThumbnailClickListener {
            tvDemoState.text = getString(R.string.upload_progress_demo_state_thumb_click)
            logI(TAG, "thumbnail clicked")
        }
    }

    private fun bindActions() {
        btnStart.setOnClickListener {
            startSimulation()
        }

        btnComplete.setOnClickListener {
            stopSimulation()
            simulatedProgress = 100
            uploadFragment.updateProgress(100, animate = true)
            tvDemoState.text = getString(R.string.upload_progress_demo_state_complete)
        }

        btnClose.setOnClickListener {
            stopSimulation()
            uploadFragment.close(enableAnimation = swCloseAnim.isChecked)
            tvDemoState.text = getString(R.string.upload_progress_demo_state_closed)
        }
    }

    private fun startSimulation() {
        stopSimulation()

        simulatedProgress = 0
        uploadFragment.resetAndShow(0)
        tvDemoState.text = getString(R.string.upload_progress_demo_state_uploading)

        val task = object : Runnable {
            override fun run() {
                val step = Random.nextInt(6, 15)
                simulatedProgress = min(100, simulatedProgress + step)
                uploadFragment.updateProgress(simulatedProgress, animate = true)

                if (simulatedProgress >= 100) {
                    tvDemoState.text = getString(R.string.upload_progress_demo_state_complete)
                    logD(TAG, "startSimulation: reached 100%")
                    return
                }

                handler.postDelayed(this, 420L)
            }
        }

        uploadTicker = task
        handler.postDelayed(task, 300L)
    }

    private fun stopSimulation() {
        val task = uploadTicker ?: return
        handler.removeCallbacks(task)
        uploadTicker = null
    }
}
