package com.vam.demov

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.vam.demov.camera.CameraPreviewActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnTiltDemo).setOnClickListener {
            startActivity(Intent(this, TiltDemoActivity::class.java))
        }

        findViewById<Button>(R.id.btnDialogTopLiftDemo).setOnClickListener {
            startActivity(Intent(this, DialogTopLiftDemoActivity::class.java))
        }

        findViewById<Button>(R.id.btnUploadProgressDemo).setOnClickListener {
            startActivity(Intent(this, UploadProgressDemoActivity::class.java))
        }

        findViewById<Button>(R.id.btnCenterTabDemo).setOnClickListener {
            startActivity(Intent(this, CenterTabDemoActivity::class.java))
        }

        findViewById<Button>(R.id.btnCameraPreview).setOnClickListener {
            startActivity(Intent(this, CameraPreviewActivity::class.java))
        }
    }
}
