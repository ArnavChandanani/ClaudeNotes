package com.example.booxnotes

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
        const val ACTION_SET_PROJECTION = "set_projection"
        const val TARGET_WIDTH = 1300
        const val DOUBLE_TAP_MS = 250L

        const val TEST_JSON = """
        {"mode":"mark","blank_cells":["F2","G4","B10"],
         "annotations":[
          {"type":"text","content":"Good working","cell":"E2"},
          {"type":"tick","cell":"H2"},
          {"type":"cross","cell":"H5"},
          {"type":"text","content":"Recheck this step","cell":"C10"}
        ]}
        """
    }

    private lateinit var wm: WindowManager
    private var dot: DotView? = null
    private var menu: View? = null
    private var scrim: View? = null
    private var canvasView: OverlayCanvas? = null
    private var typeface: Typeface = Typeface.SANS_SERIF

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netThread = HandlerThread("net").apply { start() }
    private val netHandler = Handler(netThread.looper)

    private var mediaProjection: MediaProjection? = null
    private var capturing = false

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun currentModel() = ClaudeClient.Model.from(prefs().getString("model", null))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotice()
        typeface = runCatching { Typeface.createFromAsset(assets, "handwriting.ttf") }
            .getOrDefault(Typeface.SANS_SERIF)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addCanvasOverlay()
        addDot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_PROJECTION) {
            val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")
            if (code == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(code, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { mediaProjection = null }
                }, mainHandler)
                doCapture()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotice() {
        val id = "overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Overlay", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, id)
            .setContentTitle("Claude Overlay running")
            .setContentText("Tap: ask Claude · Double-tap: clear · Long-press: model & tools")
            .setSmallIcon(android.R.drawable.ic_menu_edit).build()
        startForeground(1, n)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addCanvasOverlay() {
        val view = OverlayCanvas(this, typeface)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, lp); canvasView = view
    }

    private data class Ann(val type: String, val content: String, val cell: String)

    fun renderAnnotations(rawJson: String) {
        val cleaned = rawJson.replace("\r", "").replace("```json", "").replace("```", "").trim()
        val obj = try {
            JSONObject(cleaned.substring(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            mainHandler.post { Toast.makeText(this, "Couldn't read reply as JSON", Toast.LENGTH_LONG).show() }
            return
        }

        val mode = obj.optString("mode", "")
        val parsed = ArrayList<Ann>()
        try {
            val arr = obj.getJSONArray("annotations")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val type = a.optString("type", "").lowercase()
                if (type.isEmpty()) continue
                // Belt and braces: in answer mode, strip ticks/crosses even if the model slipped.
                if (mode == "answer" && type != "text") continue
                parsed.add(Ann(type, a.optString("content", ""), a.optString("cell", "")))
            }
        } catch (e: Exception) {
            mainHandler.post { Toast.makeText(this, "Reply had no annotations array", Toast.LENGTH_LONG).show() }
            return
        }
        if (parsed.isEmpty()) {
            mainHandler.post { Toast.makeText(this, "No annotations returned", Toast.LENGTH_SHORT).show() }
            return
        }

        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val cw = screenW / Grid.COLS
        val ch = screenH / Grid.ROWS

        // Reading order; never stack two annotations in one cell — nudge down instead.
        val ordered = parsed.sortedBy { a ->
            val p = Grid.parse(a.cell); if (p == null) 999 else p.second * Grid.COLS + p.first
        }
        val used = HashSet<String>()
        var delay = 0L

        for (a in ordered) {
            var (col, row) = Grid.parse(a.cell) ?: (3 to 5)   // centre-ish fallback
            var key = Grid.label(col, row)
            var guard = 0
            while (key in used && guard < Grid.ROWS) {
                row = (row + 1).coerceAtMost(Grid.ROWS - 1)
                key = Grid.label(col, row); guard++
            }
            used.add(key)

            val cellLeft = col * cw
            val cellTop = row * ch

            mainHandler.postDelayed({
                when (a.type) {
                    "text" -> {
                        val needed = canvasView?.measureText(a.content) ?: 0f
                        var x = cellLeft + cw * 0.08f
                        if (x + needed > screenW - 16f) x = (screenW - 16f - needed).coerceAtLeast(8f)
                        canvasView?.addText(a.content, x, cellTop + ch * 0.70f)
                    }
                    "tick"  -> canvasView?.addMark(OverlayCanvas.Type.TICK,
                                    cellLeft + cw * 0.5f - 26f, cellTop + ch * 0.5f)
                    "cross" -> canvasView?.addMark(OverlayCanvas.Type.CROSS,
                                    cellLeft + cw * 0.5f - 23f, cellTop + ch * 0.5f)
                }
            }, delay)
            delay += 500L
        }
    }

    private fun requestCapture() {
        if (mediaProjection != null) doCapture()
        else startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun doCapture() {
        val mp = mediaProjection ?: return
        if (capturing) return
        capturing = true
        setOverlayVisible(false)
        mainHandler.postDelayed({ grabFrame(mp) }, 250)
    }

    private fun grabFrame(mp: MediaProjection) {
        val m = resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels; val dpi = m.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        var done = false
        fun cleanup() {
            runCatching { vd?.release() }; runCatching { reader.close() }
            capturing = false; setOverlayVisible(true)
        }
        try {
            vd = mp.createVirtualDisplay("cap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        } catch (e: Exception) {
            mediaProjection = null; cleanup()
            mainHandler.post { Toast.makeText(this, "Capture session lost — tap again", Toast.LENGTH_SHORT).show() }
            return
        }
        reader.setOnImageAvailableListener({ r ->
            if (done) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            done = true
            try {
                val plane = image.planes[0]; val buffer = plane.buffer
                val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmpW = w + rowPadding / pixelStride
                val tmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                tmp.copyPixelsFromBuffer(buffer)
                val full = Bitmap.createBitmap(tmp, 0, 0, w, h)
                image.close()
                onCaptured(full)
            } catch (e: Exception) {
                mainHandler.post { Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally { cleanup() }
        }, mainHandler)
    }

    private fun onCaptured(full: Bitmap) {
        val scale = TARGET_WIDTH.toFloat() / full.width
        val small = Bitmap.createScaledBitmap(full, TARGET_WIDTH, (full.height * scale).toInt(), true)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            mainHandler.post { Toast.makeText(this, "No API key — set it in the app first", Toast.LENGTH_LONG).show() }
            return
        }
        val model = currentModel()
        mainHandler.post { Toast.makeText(this, "Asking ${model.label}…", Toast.LENGTH_SHORT).show() }
        netHandler.post {
            val res = ClaudeClient.annotate(apiKey, small, model)
            mainHandler.post {
                if (res.error != null) Toast.makeText(this, "Claude error: ${res.error}", Toast.LENGTH_LONG).show()
                else if (res.json != null) renderAnnotations(res.json)
            }
        }
    }

    private fun addDot() {
        val v = DotView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longFired = false; var pendingSingle = false
        val longRun = Runnable { longFired = true; showMenu(lp) }
        val singleRun = Runnable { pendingSingle = false; requestCapture() }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    mainHandler.postDelayed(longRun, 450); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; mainHandler.removeCallbacks(longRun) }
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longRun)
                    if (!moved && !longFired) {
                        if (pendingSingle) {
                            pendingSingle = false; mainHandler.removeCallbacks(singleRun); canvasView?.clearAll()
                        } else {
                            pendingSingle = true; mainHandler.postDelayed(singleRun, DOUBLE_TAP_MS)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { mainHandler.removeCallbacks(longRun); true }
                else -> false
            }
        }
        wm.addView(v, lp); dot = v
    }

    private fun showMenu(anchor: WindowManager.LayoutParams) {
        dismissMenu()
        val s = View(this).apply { setOnTouchListener { _, _ -> dismissMenu(); true } }
        wm.addView(s, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)); scrim = s
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#222222")); setPadding(8, 8, 8, 8)
        }
        fun item(label: String, act: () -> Unit) {
            m.addView(Button(this).apply { text = label; setOnClickListener { act(); dismissMenu() } })
        }
        // Model picker — filled dot marks the active model, persists across restarts.
        val cur = currentModel()
        for (mod in ClaudeClient.Model.entries) {
            item((if (mod == cur) "● " else "○ ") + mod.label) {
                prefs().edit().putString("model", mod.name).apply()
                Toast.makeText(this, "Model: ${mod.label} (${mod.note})", Toast.LENGTH_SHORT).show()
            }
        }
        item("test JSON") { renderAnnotations(TEST_JSON) }
        item("clear") { canvasView?.clearAll() }
        wm.addView(m, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = anchor.x + 200; y = anchor.y }); menu = m
    }

    private fun dismissMenu() {
        menu?.let { runCatching { wm.removeView(it) } }; menu = null
        scrim?.let { runCatching { wm.removeView(it) } }; scrim = null
    }

    fun setOverlayVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        dot?.visibility = vis; canvasView?.visibility = vis
        if (!visible) dismissMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null; dismissMenu()
        runCatching { mediaProjection?.stop() }; mediaProjection = null
        runCatching { netThread.quitSafely() }
        dot?.let { runCatching { wm.removeView(it) } }
        canvasView?.let { runCatching { wm.removeView(it) } }
    }
}

class DotView(context: Context) : View(context) {
    private val disc = Paint().apply { isAntiAlias = true; color = Color.parseColor("#141414"); style = Paint.Style.FILL }
    private val ring = Paint().apply { isAntiAlias = true; color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val mark = Paint().apply {
        isAntiAlias = true; color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    override fun onMeasure(w: Int, h: Int) {
        val s = (resources.displayMetrics.density * 69).toInt(); setMeasuredDimension(s, s)
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 4f
        c.drawCircle(cx, cy, r, disc); c.drawCircle(cx, cy, r, ring)
        val a = r * 0.5f; val b = r * 0.36f
        c.drawLine(cx, cy - a, cx, cy + a, mark); c.drawLine(cx - a, cy, cx + a, cy, mark)
        c.drawLine(cx - b, cy - b, cx + b, cy + b, mark); c.drawLine(cx - b, cy + b, cx + b, cy - b, mark)
    }
}

class OverlayCanvas(context: Context, typeface: Typeface) : View(context) {
    enum class Type { TEXT, TICK, CROSS }
    private class Item(val type: Type, val text: String, val x: Float, val y: Float) { var progress = 0f }
    companion object { const val FRAME_MS = 40L; const val STEP = 0.045f }
    private val items = mutableListOf<Item>()
    private val handler = Handler(Looper.getMainLooper())
    private var animating = false
    private val textPaint = Paint().apply {
        isAntiAlias = true; color = Color.BLACK; textSize = 62f; this.typeface = typeface
        isFakeBoldText = true; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.6f
    }
    private val markPaint = Paint().apply {
        isAntiAlias = true; color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val measure = PathMeasure()
    private fun ease(p: Float): Float = p * p * (3f - 2f * p)
    fun measureText(text: String): Float = textPaint.measureText(text)
    fun addText(text: String, x: Float, y: Float) { items.add(Item(Type.TEXT, text, x, y)); start() }
    fun addMark(type: Type, x: Float, y: Float) { items.add(Item(type, "", x, y)); start() }
    fun clearAll() { items.clear(); invalidate() }
    private fun start() { if (!animating) { animating = true; handler.post(loop) } else invalidate() }
    private val loop = object : Runnable {
        override fun run() {
            var any = false
            for (it in items) if (it.progress < 1f) { it.progress = (it.progress + STEP).coerceAtMost(1f); any = true }
            invalidate(); if (any) handler.postDelayed(this, FRAME_MS) else animating = false
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (it in items) when (it.type) {
            Type.TEXT -> drawText(canvas, it); Type.TICK -> drawTick(canvas, it); Type.CROSS -> drawCross(canvas, it)
        }
    }
    private fun drawText(canvas: Canvas, it: Item) {
        val e = ease(it.progress); val full = textPaint.measureText(it.text)
        textPaint.alpha = (60 + 195 * e).toInt().coerceIn(0, 255)
        canvas.save(); canvas.clipRect(it.x, it.y - 74f, it.x + full * e, it.y + 28f)
        canvas.drawText(it.text, it.x, it.y, textPaint); canvas.restore(); textPaint.alpha = 255
    }
    private fun drawTick(canvas: Canvas, it: Item) {
        val s = 52f
        val p = Path().apply { moveTo(it.x, it.y); lineTo(it.x + s * 0.35f, it.y + s * 0.42f); lineTo(it.x + s, it.y - s * 0.5f) }
        drawPartial(canvas, p, ease(it.progress))
    }
    private fun drawCross(canvas: Canvas, it: Item) {
        val s = 46f
        val a = Path().apply { moveTo(it.x, it.y - s / 2); lineTo(it.x + s, it.y + s / 2) }
        val b = Path().apply { moveTo(it.x + s, it.y - s / 2); lineTo(it.x, it.y + s / 2) }
        val e = ease(it.progress)
        drawPartial(canvas, a, (e * 2f).coerceAtMost(1f))
        if (e > 0.5f) drawPartial(canvas, b, ((e - 0.5f) * 2f).coerceAtMost(1f))
    }
    private fun drawPartial(canvas: Canvas, path: Path, progress: Float) {
        if (progress <= 0f) return
        measure.setPath(path, false)
        val seg = Path(); measure.getSegment(0f, measure.length * progress, seg, true)
        canvas.drawPath(seg, markPaint)
    }
}
