package com.example.booxnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Shared coordinate system between Claude and the overlay.
 * The same grid is burned into the image we send AND used to place annotations,
 * so "D7" means the identical patch of screen on both sides.
 */
object Grid {
    const val COLS = 8
    const val ROWS = 12
    private const val LETTERS = "ABCDEFGH"

    fun label(col: Int, row: Int) = "${LETTERS[col]}${row + 1}"

    /** "d7" / "D7" -> (col=3, row=6). Null if it isn't a valid cell. */
    fun parse(cell: String?): Pair<Int, Int>? {
        val s = (cell ?: return null).trim().uppercase()
        if (s.length < 2) return null
        val col = LETTERS.indexOf(s[0])
        val row = (s.drop(1).filter { it.isDigit() }.toIntOrNull() ?: return null) - 1
        if (col !in 0 until COLS || row !in 0 until ROWS) return null
        return col to row
    }

    /**
     * Draws the reference grid onto a copy of the capture.
     * Blue, so the model can never confuse grid lines with black handwriting,
     * and semi-transparent so it doesn't obscure the notes underneath.
     */
    fun burnInto(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cw = w / COLS
        val ch = h / ROWS

        val line = Paint().apply {
            color = Color.argb(85, 0, 110, 235); strokeWidth = 2f; isAntiAlias = false
        }
        val tag = Paint().apply {
            color = Color.argb(165, 0, 100, 220); textSize = ch * 0.17f
            isAntiAlias = true; isFakeBoldText = true
        }

        for (i in 1 until COLS) c.drawLine(i * cw, 0f, i * cw, h, line)
        for (j in 1 until ROWS) c.drawLine(0f, j * ch, w, j * ch, line)
        for (i in 0 until COLS) for (j in 0 until ROWS)
            c.drawText(label(i, j), i * cw + cw * 0.05f, j * ch + tag.textSize * 1.05f, tag)

        return bmp
    }
}
