package com.example.booxnotes

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {

    private const val MODEL = "claude-haiku-4-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    val PROMPT = """
You look at a photo of handwritten notes and annotate it.

STEP 1 — Check for an instruction to you:
Look for a line that starts with "@". That line is a command addressed to YOU, not part of the notes to grade.
- If an "@" line exists: do exactly what it asks (e.g. add a note, answer a question). Do NOT add ticks or crosses. Do NOT mark the "@" line itself.
- If there is NO "@" line: verify the work — tick correct items, cross clear mistakes, add short notes where needed.

STEP 2 — Position everything on a GRID:
The page is divided into 20 rows (1=top ... 20=bottom) and 5 columns (1=far left ... 5=far right).
For each annotation, give the row and column of an EMPTY cell near what it refers to. Never place a mark on top of writing — pick a nearby blank cell (often the right columns 4-5, or an empty row between lines).

Reply with ONLY this JSON, nothing else:
{"annotations":[{"type":"text","content":"...","row":<1-20>,"col":<1-5>},{"type":"tick","row":<1-20>,"col":<1-5>},{"type":"cross","row":<1-20>,"col":<1-5>}]}
- type is "text", "tick", or "cross"; only "text" has "content".
- row is 1-20, col is 1-5, both integers.
- Keep content to a few words. Output nothing outside the JSON.
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
                    put("text", PROMPT)
                })
                val msg = JSONObject().apply { put("role", "user"); put("content", content) }
                put("messages", JSONArray().put(msg))
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 30000; readTimeout = 60000
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
                    403 -> "forbidden (403) — key revoked or no access"
                    429 -> "rate limited (429)"
                    else -> "HTTP $code"
                }
                return Result(null, safe)
            }

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
