package com.example.booxnotes

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

class CaptureActivity : Activity() {

    private lateinit var mpm: MediaProjectionManager
    private val REQ = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SET_PROJECTION
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
