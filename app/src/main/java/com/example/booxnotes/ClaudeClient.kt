package com.example.booxnotes

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Anthropic Messages API client for a single image + prompt -> text reply.
 * Runs on a background thread (caller must not be on the main thread).
 * Key is passed in per-call; never stored here, never logged.
 */
object ClaudeClient {

    private const val MODEL = "claude-haiku-4-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    fun promptFor(w: Int, h: Int): String = """
You annotate a photo of handwritten notes. The image is $w pixels wide and $h pixels tall.

If a line starts with "@", treat that whole line as a command to you (not content to grade) and do what it asks; never mark an "@" line. Otherwise, verify the work: tick correct items, cross clear errors, and add a short note where something needs fixing. If you are unsure whether something is wrong, add a note rather than a cross.

Reply with ONLY this JSON, nothing else:
{"annotations":[{"type":"text","content":"...","x":<int>,"y":<int>},{"type":"tick","x":<int>,"y":<int>},{"type":"cross","x":<int>,"y":<int>}]}
x,y are pixel positions in THIS image; origin top-left; x in 0..$w, y in 0..$h. Use integer pixels, not percentages. Put each mark in empty space near what it refers to; prefer the right margin for ticks/crosses and gaps between lines for notes; never cover handwriting. Keep content to a few words. Output nothing outside the JSON.
""".trim()

    data class Result(val json: String?, val error: String?)

    fun annotate(apiKey: String, image: Bitmap): Result {
        return try {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 1024)
                val content = JSONArray()
                content.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", b64)
                    })
                })
                content.put(JSONObject().apply {
                    put("type", "text")
                    put("text", promptFor(image.width, image.height))
                })
                val msg = JSONObject().apply { put("role", "user"); put("content", content) }
                put("messages", JSONArray().put(msg))
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader().use { it.readText() }

            if (code !in 200..299) return Result(null, "HTTP $code: ${resp.take(300)}")

            // Extract the text block from the response content array.
            val obj = JSONObject(resp)
            val contentArr = obj.getJSONArray("content")
            val sb = StringBuilder()
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
