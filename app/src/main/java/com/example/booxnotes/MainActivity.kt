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
            text = "CLAUDE OVERLAY  v2.3"; textSize = 24f; setTextColor(Color.BLACK)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24) }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "API key (stored only on this device):"; textSize = 14f; setPadding(0, 20, 0, 6)
        })
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "sk-ant-..."
            setText(prefs.getString("api_key", ""))
        }
        root.addView(keyField)
        root.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                prefs.edit().putString("api_key", keyField.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Key saved on device", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        })

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
        val ok = Settings.canDrawOverlays(this)
        status.text = buildString {
            append(if (ok) "Overlay permission: GRANTED\n" else "Overlay permission: NOT granted\n")
            append(if (hasKey) "API key: saved\n" else "API key: not set\n")
            append("Finger-tap dot: capture+ask Claude · Double-tap: clear · Long-press: tools")
        }
        status.setTextColor(if (ok && hasKey) Color.rgb(0, 130, 0) else Color.rgb(180, 0, 0))
    }

    override fun onResume() { super.onResume(); refreshStatus() }
}
