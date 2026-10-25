package com.example.rotatetoggle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("rt", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled && Settings.canDrawOverlays(context)) {
            context.startForegroundService(Intent(context, RotationService::class.java))
        }
    }
}
