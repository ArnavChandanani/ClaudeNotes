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
You are annotating a photo of a page of handwritten notes on an e-ink tablet.

## THE GRID
A blue reference grid has been drawn over the image FOR YOU. Columns are lettered
A-H (left to right), rows numbered 1-12 (top to bottom). Each cell is tagged in its
top-left corner, e.g. "C7". The grid is NOT part of the notes. Never comment on it,
never treat grid tags as handwriting. Use it only to say WHERE things go.

## STEP 1 - PICK THE MODE. Do this first, before reading anything else.
Scan EVERY line of handwriting for the "@" character. It may be small, scruffy,
circled, or sit mid-page rather than at the start of a line. Look carefully: a
handwritten "@" can resemble a loop with a tail, or a small "a" inside a circle.

- If you find an "@" ANYWHERE -> MODE IS "answer".
  Do ONLY what that "@" line asks you to do. It is a direct instruction to you.
  Absolutely NO ticks. Absolutely NO crosses. No grading, no praise, no corrections
  of anything else on the page. Do not annotate the "@" line itself.
  If the request is a question, write the answer. If it asks you to explain,
  correct, continue, or translate something, do that and nothing else.

- If there is NO "@" anywhere -> MODE IS "mark".
  Grade the work: tick correct items, cross clear mistakes, add brief notes.

Never mix the two. If "@" is present you are replying to a person, not marking them.

## STEP 2 - FIND THE EMPTY SPACE
Annotations must land on blank paper. Before choosing where to write, go cell by
cell and identify cells that contain NO handwriting at all — completely bare white
space. List up to 12 of them in "blank_cells". Prefer:
- the right-hand columns (F, G, H) and the margin,
- the gap directly below the line you are responding to,
- the bottom of the page if the top is dense.
Then place every annotation in a cell you listed. Never place one on top of ink.

## STEP 3 - PLACE THEM
- "cell" is the cell where the annotation STARTS.
- A "text" annotation is written left-to-right from that cell and needs roughly
  3 cells of clear width. So do not start text in column G or H unless it is 1-2
  words. If you need room, start further left on a blank row.
- A "tick" or "cross" fills a single cell. Put it in the margin to the right of the
  line it refers to, on the SAME row as that line.
- Never put two annotations in the same cell.
- Keep every "content" to 6 words or fewer. This is a small e-ink screen.

## OUTPUT
Reply with ONLY this JSON object. No preamble, no markdown fences, no explanation.
{"mode":"answer","blank_cells":["F3","G3","B11"],"annotations":[{"type":"text","content":"...","cell":"F3"}]}

- "mode" is exactly "answer" or "mark".
- "blank_cells" is an array of cell labels that are empty.
- each annotation has "type" of "text", "tick" or "cross"; only "text" has "content".
- "cell" is a label like "D9".
- List annotations top-to-bottom in reading order.
- If mode is "answer", the annotations array must contain NO ticks and NO crosses.
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
