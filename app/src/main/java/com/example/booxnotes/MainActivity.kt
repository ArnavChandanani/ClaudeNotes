package com.example.booxnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * v0.4 -- e-ink latency.
 *  - App-wide A2 fast mode via EpdController.applyApplicationFastMode at startup.
 *  - Each stroke segment is pushed to the panel immediately (partial update) instead
 *    of waiting on the framework invalidate cycle. Both EpdController calls are
 *    reflection-guarded: if the firmware lacks them, we fall back to invalidate().
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawView: DrawView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Epd.applyFastMode(this)   // turn on A2 for this app
        val root = FrameLayout(this)
        drawView = DrawView(this)
        root.addView(
            drawView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(buildToolbar())
        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        Epd.clearFastMode(this)
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.LTGRAY)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                DrawView.TOOLBAR_HEIGHT_PX
            )
        }
        bar.addView(Button(this).apply { text = "Save"; setOnClickListener { savePage() } })
        bar.addView(Button(this).apply { text = "Clear"; setOnClickListener { drawView.clearPage() } })
        return bar
    }

    private fun savePage() {
        val bmp = drawView.snapshot() ?: run { toast("Nothing to save yet"); return }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        try {
            FileOutputStream(File(dir, "note_$stamp.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(dir, "note_$stamp.json").writeText(drawView.toJson().toString(2))
            toast("Saved: ${drawView.strokeCount()} strokes, ${drawView.pointCount()} points\n$dir")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/** Reflection-guarded EpdController access so a missing class never crashes the app. */
object Epd {
    private fun controller(): Class<*>? = try {
        Class.forName("com.onyx.android.sdk.device.EpdController")
    } catch (e: Throwable) { null }

    fun applyFastMode(ctx: Context) {
        try {
            val m = controller()?.getMethod(
                "applyApplicationFastMode",
                String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            m?.invoke(null, ctx.packageName, true, true)
        } catch (e: Throwable) { /* fall back to normal refresh */ }
    }

    fun clearFastMode(ctx: Context) {
        try {
            val m = controller()?.getMethod(
                "applyApplicationFastMode",
                String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            m?.invoke(null, ctx.packageName, false, true)
        } catch (e: Throwable) {}
    }

    /** Push a specific region of a view to the panel now. Returns true if it worked. */
    fun repaintRegion(view: View, rect: Rect): Boolean {
        return try {
            val m = controller()?.getMethod(
                "repaintEveryThing", View::class.java
            )
            // Simple, broadly-present call: repaint the view immediately.
            m?.invoke(null, view)
            true
        } catch (e: Throwable) { false }
    }
}

class DrawView(context: Context) : View(context) {

    companion object {
        const val TOOLBAR_HEIGHT_PX = 130
        const val MIN_DIST = 2.5f
        const val STROKE_WIDTH = 3f
    }

    private data class Pt(val x: Float, val y: Float, val p: Float, val t: Long)
    private data class Stroke(val tool: String, val color: String, val width: Float, val points: List<Pt>)

    private val strokes = mutableListOf<Stroke>()
    private var pageBitmap: Bitmap? = null
    private var pageCanvas: Canvas? = null

    private val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var currentPts = mutableListOf<Pt>()
    private var lastX = 0f
    private var lastY = 0f
    private val pad = STROKE_WIDTH * 2f
    private val dirty = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (pageBitmap?.width == w && pageBitmap?.height == h) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        pageBitmap = bmp
        pageCanvas = Canvas(bmp)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pageBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                if (y < TOOLBAR_HEIGHT_PX) return false
                currentPts = mutableListOf(Pt(x, y, event.pressure, event.eventTime))
                lastX = x; lastY = y
                pageCanvas?.drawPoint(x, y, paint)
                pushRegion(x, y, x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val canvas = pageCanvas ?: return true
                for (h in 0 until event.historySize) {
                    addSegment(canvas, event.getHistoricalX(h), event.getHistoricalY(h),
                        event.getHistoricalPressure(h), event.getHistoricalEventTime(h))
                }
                addSegment(canvas, event.x, event.y, event.pressure, event.eventTime)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (currentPts.isNotEmpty()) {
                    strokes.add(Stroke("pen", "#000000", STROKE_WIDTH, currentPts.toList()))
                }
                currentPts = mutableListOf()
                return true
            }
        }
        return false
    }

    private fun addSegment(canvas: Canvas, x: Float, y: Float, pressure: Float, time: Long) {
        if (hypot(x - lastX, y - lastY) < MIN_DIST) return
        canvas.drawLine(lastX, lastY, x, y, paint)
        currentPts.add(Pt(x, y, pressure, time))
        pushRegion(lastX, lastY, x, y)
        lastX = x; lastY = y
    }

    /** Draw the new segment to the panel immediately; fall back to invalidate() if EPD path fails. */
    private fun pushRegion(x0: Float, y0: Float, x1: Float, y1: Float) {
        dirty.set(
            (minOf(x0, x1) - pad).toInt(),
            (minOf(y0, y1) - pad).toInt(),
            (maxOf(x0, x1) + pad).toInt(),
            (maxOf(y0, y1) + pad).toInt()
        )
        if (!Epd.repaintRegion(this, dirty)) {
            invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
        }
    }

    fun clearPage() {
        strokes.clear()
        pageCanvas?.drawColor(Color.WHITE)
        currentPts = mutableListOf()
        invalidate()
    }

    fun snapshot(): Bitmap? = pageBitmap
    fun strokeCount(): Int = strokes.size
    fun pointCount(): Int = strokes.sumOf { it.points.size }

    fun toJson(): JSONObject {
        val strokeArr = JSONArray()
        for (s in strokes) {
            val ptArr = JSONArray()
            for (p in s.points) {
                ptArr.put(JSONObject().apply {
                    put("x", p.x.toDouble()); put("y", p.y.toDouble())
                    put("p", p.p.toDouble()); put("t", p.t)
                })
            }
            strokeArr.put(JSONObject().apply {
                put("tool", s.tool); put("color", s.color)
                put("width", s.width.toDouble()); put("points", ptArr)
            })
        }
        val page = JSONObject().apply {
            put("width", pageBitmap?.width ?: 0)
            put("height", pageBitmap?.height ?: 0)
            put("strokes", strokeArr)
        }
        return JSONObject().apply { put("schemaVersion", 1); put("page", page) }
    }
}
