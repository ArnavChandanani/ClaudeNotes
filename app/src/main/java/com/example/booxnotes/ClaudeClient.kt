package com.example.booxnotes

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    enum class Model(val id: String, val label: String, val note: String) {
        HAIKU("claude-haiku-4-5", "Haiku 4.5", "fastest, cheapest"),
        SONNET("claude-sonnet-5", "Sonnet 5", "balanced — good default"),
        OPUS("claude-opus-5", "Opus 5", "smartest, most expensive");

        companion object {
            fun from(key: String?) = entries.firstOrNull { it.name == key } ?: SONNET
        }
    }

    val PROMPT = """
You are marking and annotating a page of handwritten notes on an e-ink tablet.
Write like a good teacher with a red pen: specific, substantive, willing to explain.

## THE GRID
A blue reference grid has been drawn over the image FOR YOU. Columns are lettered
A-H (left to right), rows numbered 1-12 (top to bottom). Each cell is tagged in its
top-left corner, e.g. "C7". The grid is NOT part of the notes. Never comment on it,
never treat grid tags as handwriting. Use it only to say WHERE things go.

## STEP 1 - PICK THE MODE. Do this first.
Scan EVERY line of handwriting for the "@" character. It may be small, scruffy,
circled, or sit mid-page rather than at the start of a line. A handwritten "@" often
looks like a loop with a tail, or a small "a" inside a circle.

- "@" found ANYWHERE -> mode is "answer".
  Do ONLY what that line asks. NO ticks. NO crosses. No grading of anything else.
  Do not annotate the "@" line itself. Answer properly and completely.

- No "@" anywhere -> mode is "mark".
  Grade the work.

Be honest about which it is. Do not claim "answer" mode unless you can point to an
actual "@" on the page - claiming it wrongly suppresses all marking.

## STEP 2 - FIND THE EMPTY SPACE
Annotations must land on blank paper. Go cell by cell and identify cells containing
NO handwriting at all. List up to 12 in "blank_cells". Prefer the right-hand columns
(F, G, H), the gap directly below the relevant line, and the bottom of the page.
Every annotation must start in a cell you listed. Never write on top of ink.

## STEP 3 - HOW MUCH TO WRITE
A one-word annotation is a wasted annotation. Be genuinely useful.

- Text wraps automatically to the right edge and may run to 3 or 4 lines, so you
  have room for real sentences. Aim for 10-25 words per text annotation. Use "\n"
  inside content only if you want a deliberate line break.
- Because text flows rightwards and downwards, start text annotations in columns
  A-E. Reserve F-H for ticks and crosses.

IN "mark" MODE - this part is mandatory:
- Give 4 to 7 annotations total.
- Put a "tick" beside every step or answer that is correct.
- Put a "cross" beside every clear mistake, AND a text annotation nearby explaining
  what went wrong and how to fix it.
- Add at least one text annotation with real feedback - what was done well, what to
  improve, or the correct method.
- A "mark" reply containing zero ticks and zero crosses is INVALID. If the page has
  any work on it at all, some of it is either right or wrong - say which.

IN "answer" MODE:
- 1 to 3 text annotations. Give the actual answer plus the reasoning or working
  behind it, not a bare result. Explain as if the person will read only this.

## PLACEMENT RULES
- "cell" is where the annotation STARTS.
- A tick or cross occupies one cell; put it in the margin on the SAME row as the
  line it judges, so it is obvious what it refers to.
- Never put two annotations in the same cell, and leave a row of clearance below
  each text annotation for it to wrap into.

## OUTPUT
Reply with ONLY this JSON object. No preamble, no markdown fences, no explanation.
{"mode":"mark","blank_cells":["F3","G3","B11","C11"],"annotations":[{"type":"tick","cell":"G3"},{"type":"cross","cell":"G6"},{"type":"text","content":"Sign error on line 3 - subtracting 4x should give -4x, not +4x. Redo from there.","cell":"B11"}]}

- "mode" is exactly "answer" or "mark".
- "blank_cells" is an array of empty cell labels.
- each annotation has "type" of "text", "tick" or "cross"; only "text" has "content".
- List annotations top-to-bottom in reading order.
""".trim()

    data class Result(val json: String?, val error: String?)

    fun annotate(apiKey: String, image: Bitmap, model: Model): Result {
        return try {
            // Burn the shared grid in so Claude sees the same coordinates we render with.
            val gridded = Grid.burnInto(image)
            val baos = ByteArrayOutputStream()
            gridded.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            if (gridded !== image) gridded.recycle()

            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", b64)
                })
            })
            userContent.put(JSONObject().apply { put("type", "text"); put("text", PROMPT) })

            val msgs = JSONArray()
            msgs.put(JSONObject().apply { put("role", "user"); put("content", userContent) })
            // No assistant prefill: these models think by default, and the API rejects
            // prefill when thinking is on. The brace-extraction in the parser covers us.

            val body = JSONObject().apply {
                put("model", model.id)
                put("max_tokens", 4000)   // headroom — thinking tokens count against this
                put("messages", msgs)
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 30000; readTimeout = 90000
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val safe = when (code) {
                    401 -> "auth failed (401) — check your API key"
                    403 -> "forbidden (403) — key revoked, or no access to ${model.label}"
                    404 -> "model not found (404) — ${model.id}"
                    429 -> "rate limited (429)"
                    529 -> "Anthropic overloaded (529) — try again"
                    else -> "HTTP $code"
                }
                return Result(null, safe)
            }

            val obj = JSONObject(resp)
            val contentArr = obj.getJSONArray("content")
            val sb = StringBuilder()      // thinking blocks are skipped; we keep only text
            for (i in 0 until contentArr.length()) {
                val block = contentArr.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            Result(sb.toString(), null)
        } catch (e: Exception) {
            Result(null, e.message ?: "request failed")
        }
    }
}
