package com.example.booxnotes

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transparent activity that captures ONE frame of the screen via MediaProjection,
 * saves it as a PNG, then triggers the stub "reply" on the overlay.
 * Riskiest device test: if the PNG is black, this Boox firmware restricts capture.
 */
class CaptureActivity : Activity() {

    private lateinit var mpm: MediaProjectionManager
    private var captured = false
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
            OverlayService.instance?.setOverlayVisible(false)
            Handler(Looper.getMainLooper()).postDelayed({ capture(resultCode, data) }, 250)
        } else {
            Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun capture(resultCode: Int, data: Intent) {
        val mp: MediaProjection = mpm.getMediaProjection(resultCode, data)
        mp.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))

        val m = resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels; val dpi = m.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        val vd = mp.createVirtualDisplay(
            "cap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmpW = w + rowPadding / pixelStride
                val tmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                tmp.copyPixelsFromBuffer(buffer)
                val bmp = Bitmap.createBitmap(tmp, 0, 0, w, h)
                image.close()

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "capture_$stamp.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

                runOnUiThread {
                    Toast.makeText(this, "Captured -> ${file.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                runCatching { vd.release() }
                runCatching { mp.stop() }
                runCatching { r.close() }
                OverlayService.instance?.setOverlayVisible(true)
                OverlayService.instance?.writeStub()
                finish()
            }
        }, Handler(Looper.getMainLooper()))
    }
}
