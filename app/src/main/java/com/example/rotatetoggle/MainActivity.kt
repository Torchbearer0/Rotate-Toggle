package com.example.rotatetoggle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var toggle: Button

    private val prefs by lazy { getSharedPreferences("rt", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        TextView(this).apply {
            text = "Rotate Toggle"
            textSize = 26f
            root.addView(this)
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            root.addView(this)
        }
        grant = Button(this).apply {
            text = "1. Allow \"Display over other apps\""
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            root.addView(this)
        }
        toggle = Button(this).apply {
            setOnClickListener { toggleService() }
            root.addView(this)
        }
        TextView(this).apply {
            text = "Keep system auto-rotate OFF. Tilt the phone sideways and a small " +
                "button appears in the lower-right corner; tap it to rotate."
            setPadding(0, pad, 0, 0)
            root.addView(this)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val canDraw = Settings.canDrawOverlays(this)
        val running = prefs.getBoolean("enabled", false) && canDraw
        grant.isEnabled = !canDraw
        toggle.isEnabled = canDraw
        toggle.text = if (running) "Stop" else "2. Start"
        status.text = when {
            !canDraw -> "Overlay permission needed."
            running -> "Running."
            else -> "Ready."
        }
    }

    private fun toggleService() {
        val svc = Intent(this, RotationService::class.java)
        if (prefs.getBoolean("enabled", false)) {
            prefs.edit().putBoolean("enabled", false).apply()
            stopService(svc)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            prefs.edit().putBoolean("enabled", true).apply()
            startForegroundService(svc)
        }
        refresh()
    }
}
