package com.example.booxnotes

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * DIAGNOSTIC BUILD -- not the real app.
 * Whole screen is a touch probe. Every touch event is printed live so we can see
 * whether the Go 10.3 delivers stylus input to a third-party app, and as what.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private val lines = ArrayDeque<String>()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = "Touch the screen with the PEN and with a FINGER.\nWaiting for input...\n"
        }
        setContentView(log)

        log.setOnTouchListener { _, e -> handle(e); true }
    }

    @SuppressLint("SetTextI18n")
    private fun handle(e: MotionEvent) {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "other(${e.actionMasked})"
        }
        val tool = when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN(${e.getToolType(0)})"
        }
        // Only log DOWN/UP and a fraction of MOVEs so the screen stays readable.
        if (e.actionMasked == MotionEvent.ACTION_MOVE && lines.size % 1 != 0) return

        val line = "$action  tool=$tool  x=${e.x.toInt()} y=${e.y.toInt()}  p=${"%.2f".format(e.pressure)}"
        lines.addLast(line)
        while (lines.size > 30) lines.removeFirst()
        log.text = "PEN + FINGER probe -- newest at bottom:\n\n" + lines.joinToString("\n")
    }
}
