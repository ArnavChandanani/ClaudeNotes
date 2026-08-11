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

/**
 * v0.2 -- pen captured via standard Android MotionEvents (confirmed working on Go 10.3).
 * Stylus only for ink; finger is left free for future pan/scroll. Vector JSON + PNG on Save.
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

    private val currentWidth = 3f
    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = currentWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var currentPath = Path()
    private var currentPts = mutableListOf<Pt>()

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
        if (!currentPath.isEmpty) canvas.drawPath(currentPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only the stylus draws. Finger is ignored here (reserved for future pan/zoom).
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y < TOOLBAR_HEIGHT_PX) return false
                currentPath = Path()
                currentPts = mutableListOf()
                currentPath.moveTo(x, y)
                currentPts.add(Pt(x, y, event.pressure, event.eventTime))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    currentPath.lineTo(hx, hy)
                    currentPts.add(Pt(hx, hy, event.getHistoricalPressure(h), event.getHistoricalEventTime(h)))
                }
                currentPath.lineTo(x, y)
                currentPts.add(Pt(x, y, event.pressure, event.eventTime))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pageCanvas?.drawPath(currentPath, paint)
                if (currentPts.isNotEmpty()) {
                    strokes.add(Stroke("pen", "#000000", currentWidth, currentPts.toList()))
                }
                currentPath = Path()
                currentPts = mutableListOf()
                invalidate()
                return true
            }
        }
        return false
    }

    fun clearPage() {
        strokes.clear()
        pageCanvas?.drawColor(Color.WHITE)
        currentPath = Path()
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
