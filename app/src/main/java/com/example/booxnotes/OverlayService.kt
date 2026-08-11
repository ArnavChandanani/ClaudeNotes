package com.example.booxnotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * v2.0 DIAGNOSTIC — not the real app.
 * The dot logs every motion event it receives (touch, hover, generic) with the tool
 * type, to an on-screen panel. Tells us whether the STYLUS reaches the overlay dot's
 * event handlers at all, and via which channel. That decides the real fix.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var dot: View? = null
    private var logView: TextView? = null
    private val lines = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addLog()
        addDot()
    }

    private fun startForegroundNotice() {
        val id = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Overlay", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, id)
            .setContentTitle("Claude Overlay — DIAGNOSTIC")
            .setContentText("Touch the dot with pen and finger; watch the log")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(1, n)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addLog() {
        val tv = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#DD000000"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 16, 16, 16)
            text = "Touch the DOT with pen, then finger.\nEvents it receives appear here.\n"
        }
        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 520 }
        wm.addView(tv, lp)
        logView = tv
    }

    private fun log(channel: String, e: MotionEvent) {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
            MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "a${e.actionMasked}"
        }
        val tool = when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            else -> "tool${e.getToolType(0)}"
        }
        val line = "$channel: $action  $tool"
        lines.addLast(line)
        while (lines.size > 18) lines.removeFirst()
        mainHandler.post {
            logView?.text = "DOT event log (newest at bottom):\n\n" + lines.joinToString("\n")
        }
    }

    private fun addDot() {
        val v = object : View(this) {
            private val p = Paint().apply { isAntiAlias = true; color = Color.parseColor("#141414") }
            private val ring = Paint().apply { isAntiAlias = true; color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
            override fun onMeasure(w: Int, h: Int) {
                val s = (resources.displayMetrics.density * 69).toInt(); setMeasuredDimension(s, s)
            }
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 4f
                c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r, ring)
            }
            // Catch hover/generic-motion channel (pen hover often arrives here).
            override fun onGenericMotionEvent(e: MotionEvent): Boolean { log("generic", e); return true }
            override fun onHoverEvent(e: MotionEvent): Boolean { log("hover", e); return true }
        }
        // Touch channel.
        v.setOnTouchListener { _, e -> log("touch", e); true }
        // Hover listener (redundant safety net for the touch/hover split).
        v.setOnHoverListener { _, e -> log("hoverL", e); true }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }
        wm.addView(v, lp)
        dot = v
    }

    override fun onDestroy() {
        super.onDestroy()
        dot?.let { runCatching { wm.removeView(it) } }
        logView?.let { runCatching { wm.removeView(it) } }
    }
}
