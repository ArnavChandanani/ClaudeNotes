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
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var canvasView: OverlayCanvas? = null
    private var typeface: Typeface = Typeface.SANS_SERIF

    private val samples = listOf(
        "Lovely handwriting!",
        "Great point here.",
        "Check this again.",
        "Nicely done.",
        "Claude was here."
    )
    private var sampleIndex = 0
    private var placed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        typeface = runCatching { Typeface.createFromAsset(assets, "handwriting.ttf") }
            .getOrDefault(Typeface.SANS_SERIF)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addCanvasOverlay()
        addPanel()
    }

    private fun startForegroundNotice() {
        val id = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Overlay", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, id)
            .setContentTitle("Claude Overlay running")
            .setContentText("Use the panel to write over your notes")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(1, n)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addCanvasOverlay() {
        val view = OverlayCanvas(this, typeface)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, lp)
        canvasView = view
    }

    private fun nextPos(): Pair<Float, Float> {
        val x = 120f
        val y = 320f + (placed % 6) * 150f
        placed++
        return x to y
    }

    private fun addPanel() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#222222"))
            setPadding(8, 8, 8, 8)
        }

        val handle = TextView(this).apply {
            text = "drag"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 10)
        }
        p.addView(handle)

        fun mkBtn(label: String, action: () -> Unit): Button =
            Button(this).apply { text = label; setOnClickListener { action() } }

        p.addView(mkBtn("write") {
            val (x, y) = nextPos()
            canvasView?.addText(samples[sampleIndex % samples.size], x, y)
            sampleIndex++
        })
        p.addView(mkBtn("tick") {
            val (x, y) = nextPos()
            canvasView?.addMark(OverlayCanvas.Type.TICK, x, y)
        })
        p.addView(mkBtn("cross") {
            val (x, y) = nextPos()
            canvasView?.addMark(OverlayCanvas.Type.CROSS, x, y)
        })
        p.addView(mkBtn("clear") {
            canvasView?.clearAll(); placed = 0
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 180 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(p, lp); true
                }
                else -> false
            }
        }

        wm.addView(p, lp)
        panel = p
    }

    override fun onDestroy() {
        super.onDestroy()
        panel?.let { runCatching { wm.removeView(it) } }
        canvasView?.let { runCatching { wm.removeView(it) } }
    }
}

class OverlayCanvas(context: Context, typeface: Typeface) : View(context) {

    enum class Type { TEXT, TICK, CROSS }

    private class Item(val type: Type, val text: String, val x: Float, val y: Float) {
        var progress = 0f
    }

    companion object {
        const val FRAME_MS = 40L
        const val STEP = 0.14f
    }

    private val items = mutableListOf<Item>()
    private val handler = Handler(Looper.getMainLooper())
    private var animating = false

    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        textSize = 58f
        this.typeface = typeface
    }
    private val markPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val measure = PathMeasure()

    fun addText(text: String, x: Float, y: Float) { items.add(Item(Type.TEXT, text, x, y)); start() }
    fun addMark(type: Type, x: Float, y: Float) { items.add(Item(type, "", x, y)); start() }
    fun clearAll() { items.clear(); invalidate() }

    private fun start() {
        if (!animating) { animating = true; handler.post(loop) } else invalidate()
    }

    private val loop = object : Runnable {
        override fun run() {
            var any = false
            for (it in items) if (it.progress < 1f) {
                it.progress = (it.progress + STEP).coerceAtMost(1f); any = true
            }
            invalidate()
            if (any) handler.postDelayed(this, FRAME_MS) else animating = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (it in items) when (it.type) {
            Type.TEXT -> drawText(canvas, it)
            Type.TICK -> drawTick(canvas, it)
            Type.CROSS -> drawCross(canvas, it)
        }
    }

    private fun drawText(canvas: Canvas, it: Item) {
        val full = textPaint.measureText(it.text)
        canvas.save()
        canvas.clipRect(it.x, it.y - 70f, it.x + full * it.progress, it.y + 24f)
        canvas.drawText(it.text, it.x, it.y, textPaint)
        canvas.restore()
    }

    private fun drawTick(canvas: Canvas, it: Item) {
        val s = 52f
        val p = Path().apply {
            moveTo(it.x, it.y)
            lineTo(it.x + s * 0.35f, it.y + s * 0.42f)
            lineTo(it.x + s, it.y - s * 0.5f)
        }
        drawPartial(canvas, p, it.progress)
    }

    private fun drawCross(canvas: Canvas, it: Item) {
        val s = 46f
        val a = Path().apply { moveTo(it.x, it.y - s / 2); lineTo(it.x + s, it.y + s / 2) }
        val b = Path().apply { moveTo(it.x + s, it.y - s / 2); lineTo(it.x, it.y + s / 2) }
        drawPartial(canvas, a, (it.progress * 2f).coerceAtMost(1f))
        if (it.progress > 0.5f) drawPartial(canvas, b, ((it.progress - 0.5f) * 2f).coerceAtMost(1f))
    }

    private fun drawPartial(canvas: Canvas, path: Path, progress: Float) {
        if (progress <= 0f) return
        measure.setPath(path, false)
        val seg = Path()
        measure.getSegment(0f, measure.length * progress, seg, true)
        canvas.drawPath(seg, markPaint)
    }
}
