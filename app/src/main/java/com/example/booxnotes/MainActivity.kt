package com.example.booxnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0 goal: prove the Onyx pen SDK delivers stylus strokes on THIS device, and that
 * we can persist those strokes as VECTOR data (not just a flattened image).
 *
 * The SDK hands us each finished stroke as a TouchPointList -- that list of points
 * IS the vector stroke. We keep it in `strokes`, render a preview into a Bitmap so
 * there is something to look at, and on Save we write BOTH:
 *   - note_*.png   : flattened image (sanity check / quick view)
 *   - note_*.json  : the vector strokes (the real storage format going forward)
 *
 * If ink appears but the JSON has 0 strokes, the hardware draws but callbacks aren't
 * firing on this unit -- the known Go 10.3 issue -- and we debug the SDK first.
 */
class MainActivity : AppCompatActivity() {

    // --- lightweight vector model (this is the shape a stored note will grow from) ---
    private data class Pt(val x: Float, val y: Float, val p: Float, val t: Long)
    private data class Stroke(
        val tool: String,
        val color: String,
        val width: Float,
        val points: List<Pt>
    )

    private lateinit var surfaceView: SurfaceView
    private var touchHelper: TouchHelper? = null

    private var pageBitmap: Bitmap? = null
    private var pageCanvas: Canvas? = null

    private val strokes = mutableListOf<Stroke>()

    private val toolbarHeightPx = 130

    private val currentTool = "fountain"
    private val currentColor = "#000000"
    private val currentWidth = 3f

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = currentWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {}
        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) {}
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points ?: return
            if (pts.isEmpty()) return
            // Keep the vector stroke.
            strokes.add(
                Stroke(
                    tool = currentTool,
                    color = currentColor,
                    width = currentWidth,
                    points = pts.map { Pt(it.x, it.y, it.pressure, it.timestamp) }
                )
            )
            // Also render into the preview bitmap.
            drawStrokeToBitmap(pts)
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {}
        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(buildToolbar())
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ensureBitmap(width, height)
                setupTouchHelper(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                touchHelper?.setRawDrawingEnabled(false)
            }
        })
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.LTGRAY)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                toolbarHeightPx
            )
        }
        bar.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { savePage() }
        })
        bar.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { clearPage() }
        })
        return bar
    }

    private fun ensureBitmap(width: Int, height: Int) {
        if (pageBitmap?.width == width && pageBitmap?.height == height) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        pageBitmap = bmp
        pageCanvas = Canvas(bmp)
    }

    private fun setupTouchHelper(width: Int, height: Int) {
        val limit = Rect(0, 0, width, height)
        val exclude = ArrayList<Rect>().apply { add(Rect(0, 0, width, toolbarHeightPx)) }

        val helper = touchHelper ?: TouchHelper.create(surfaceView, rawInputCallback).also {
            touchHelper = it
        }
        helper.setStrokeWidth(currentWidth)
        helper.setLimitRect(limit, exclude)
        helper.openRawDrawing()
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
        helper.setStrokeWidth(currentWidth)
        helper.setRawDrawingEnabled(true)
    }

    private fun drawStrokeToBitmap(pts: List<TouchPoint>) {
        val canvas = pageCanvas ?: return
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        canvas.drawPath(path, paint)
    }

    private fun savePage() {
        val bmp = pageBitmap ?: run { toast("Nothing to save yet"); return }
        val totalPoints = strokes.sumOf { it.points.size }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        try {
            // 1) preview image
            FileOutputStream(File(dir, "note_$stamp.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            // 2) vector strokes (the real format)
            val json = pageToJson(bmp.width, bmp.height)
            File(dir, "note_$stamp.json").writeText(json.toString(2))

            toast("Saved: ${strokes.size} strokes, $totalPoints points\n$dir")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    private fun pageToJson(width: Int, height: Int): JSONObject {
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
            put("width", width)
            put("height", height)
            put("strokes", strokeArr)
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("page", page)
        }
    }

    private fun clearPage() {
        touchHelper?.setRawDrawingEnabled(false)
        strokes.clear()
        pageCanvas?.drawColor(Color.WHITE)
        val holder = surfaceView.holder
        val c = holder.lockCanvas()
        if (c != null) {
            c.drawColor(Color.WHITE)
            holder.unlockCanvasAndPost(c)
        }
        touchHelper?.setRawDrawingEnabled(true)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        touchHelper?.setRawDrawingEnabled(true)
    }

    override fun onPause() {
        super.onPause()
        touchHelper?.setRawDrawingEnabled(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        touchHelper?.closeRawDrawing()
    }
}
