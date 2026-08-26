package com.example.booxnotes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(70, 120, 70, 70)
        }
        root.addView(TextView(this).apply {
            text = "CLAUDE OVERLAY  v2.4"; textSize = 24f; setTextColor(Color.BLACK)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24) }
        root.addView(status)

        // --- API key: masked, and the saved key is never shown again ---
        root.addView(TextView(this).apply {
            text = "API key (stored only on this device):"; textSize = 14f; setPadding(0, 20, 0, 6)
        })
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (!prefs.getString("api_key", "").isNullOrBlank())
                "key saved — type here only to replace it" else "sk-ant-..."
            // deliberately NOT pre-filled: the stored key never re-enters a text field
        }
        root.addView(keyField)
        root.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                val k = keyField.text.toString().trim()
                if (k.isBlank()) {
                    Toast.makeText(this@MainActivity, "Field empty — existing key kept", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("api_key", k).apply()
                    keyField.text.clear()
                    keyField.hint = "key saved — type here only to replace it"
                    Toast.makeText(this@MainActivity, "Key saved on device", Toast.LENGTH_SHORT).show()
                }
                refreshStatus()
            }
        })

        // --- Model picker (also changeable from the dot's long-press menu) ---
        root.addView(TextView(this).apply {
            text = "Model:"; textSize = 14f; setPadding(0, 24, 0, 6)
        })
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val current = ClaudeClient.Model.from(prefs.getString("model", null))
        for (m in ClaudeClient.Model.entries) {
            group.addView(RadioButton(this).apply {
                text = "${m.label} — ${m.note}"
                id = m.ordinal
                isChecked = (m == current)
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            val m = ClaudeClient.Model.entries[id]
            prefs.edit().putString("model", m.name).apply()
            Toast.makeText(this, "Model: ${m.label}", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        root.addView(group)

        root.addView(Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "2. Start overlay"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    val i = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                    Toast.makeText(this@MainActivity, "Started — open Boox Notes", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this@MainActivity, "Do step 1 first", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(Button(this).apply {
            text = "3. Stop overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Stopped", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(root)
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val hasKey = !prefs.getString("api_key", "").isNullOrBlank()
        val model = ClaudeClient.Model.from(prefs.getString("model", null))
        val ok = Settings.canDrawOverlays(this)
        status.text = buildString {
            append(if (ok) "Overlay permission: GRANTED\n" else "Overlay permission: NOT granted\n")
            append(if (hasKey) "API key: saved (hidden)\n" else "API key: not set\n")
            append("Model: ${model.label}\n")
            append("Tap dot: capture+ask · Double-tap: clear · Long-press: model & tools")
        }
        status.setTextColor(if (ok && hasKey) Color.rgb(0, 130, 0) else Color.rgb(180, 0, 0))
    }

    override fun onResume() { super.onResume(); refreshStatus() }
}
