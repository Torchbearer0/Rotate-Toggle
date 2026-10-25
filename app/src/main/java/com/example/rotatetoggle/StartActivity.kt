package com.example.rotatetoggle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Invisible activity: starts the service and closes itself immediately. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            RotationService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
