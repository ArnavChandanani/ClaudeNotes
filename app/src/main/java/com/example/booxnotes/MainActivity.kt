package com.example.booxnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v0.3 -- latency work.
 *  - Only the dirty rect around each segment is invalidated (not the whole screen).
 *  - Anti-aliasing off: faster + sharper on e-ink.
 *  - Points closer than MIN_DIST to the last kept point are dropped (fewer redraws,
 *    no visible quality loss). Raw fidelity is unchanged in spirit; we still keep
 *    pressure/time on the points we retain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawView: DrawView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        bar.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { savePage() }
        })
        bar.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { drawView.clearPage() }
        })
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

class DrawView(context: Context) : View(context) {

    companion object {
        const val TOOLBAR_HEIGHT_PX = 130
        const val MIN_DIST = 2.5f          // px; drop samples closer than this
        const val STROKE_WIDTH = 3f
    }

    private data class Pt(val x: Float, val y: Float, val p: Float, val t: Long)
    private data class Stroke(
        val tool: String,
        val color: String,
        val width: Float,
        val points: List<Pt>
    )

    private val strokes = mutableListOf<Stroke>()

    private var pageBitmap: Bitmap? = null
    private var pageCanvas: Canvas? = null

    private val paint = Paint().apply {
        isAntiAlias = false            // e-ink: hard edges are faster and sharper
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
    private val pad = STROKE_WIDTH * 2f  // dirty-rect padding so round caps aren't clipped

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
        // The committed page bitmap is the single source of truth; segments are drawn
        // into it live, so we just blit it (respecting the current clip/dirty rect).
        pageBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (y < TOOLBAR_HEIGHT_PX) return false
                currentPts = mutableListOf(Pt(x, y, event.pressure, event.eventTime))
                lastX = x; lastY = y
                // draw a dot so a tap leaves a mark
                pageCanvas?.drawPoint(x, y, paint)
                invalidate((x - pad).toInt(), (y - pad).toInt(), (x + pad).toInt(), (y + pad).toInt())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val canvas = pageCanvas ?: return true
                // historical samples first, then the current one
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
        // invalidate only the bounding box of this segment
        val l = (minOf(lastX, x) - pad).toInt()
        val t = (minOf(lastY, y) - pad).toInt()
        val r = (maxOf(lastX, x) + pad).toInt()
        val b = (maxOf(lastY, y) + pad).toInt()
        invalidate(l, t, r, b)
        lastX = x; lastY = y
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
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                    put("p", p.p.toDouble())
                    put("t", p.t)
                })
            }
            strokeArr.put(JSONObject().apply {
                put("tool", s.tool)
                put("color", s.color)
                put("width", s.width.toDouble())
                put("points", ptArr)
            })
        }
        val page = JSONObject().apply {
            put("width", pageBitmap?.width ?: 0)
            put("height", pageBitmap?.height ?: 0)
            put("strokes", strokeArr)
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("page", page)
        }
    }
}
