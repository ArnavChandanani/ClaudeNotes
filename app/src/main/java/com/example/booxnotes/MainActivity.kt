package com.example.booxnotes

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(70, 130, 70, 70)
        }

        root.addView(TextView(this).apply {
            text = "CLAUDE OVERLAY  v0.9"
            textSize = 24f
            setTextColor(Color.BLACK)
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 30, 0, 40)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(Button(this).apply {
            text = "2. Start overlay"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    val i = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(i) else startService(i)
                    Toast.makeText(this@MainActivity, "Overlay started — open Boox Notes", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Do step 1 first", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "3. Stop overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Overlay stopped", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        status.text = if (granted)
            "Overlay permission: GRANTED ✓\nTap 'Start overlay', then open Boox Notes."
        else
            "Overlay permission: NOT granted ✗\nTap step 1 and enable it for Claude Overlay."
        status.setTextColor(if (granted) Color.rgb(0, 130, 0) else Color.rgb(180, 0, 0))
    }
}
