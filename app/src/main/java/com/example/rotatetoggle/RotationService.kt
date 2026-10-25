package com.example.rotatetoggle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton

/**
 * Foreground service that:
 *  1. listens to the accelerometer-based OrientationEventListener (works even when
 *     system auto-rotate is OFF, and needs no permission),
 *  2. shows a small floating button in the lower-right corner when the phone is held
 *     in an orientation different from the current screen orientation,
 *  3. on tap, rotates the screen using a 1x1 invisible overlay window whose
 *     screenOrientation is set (no accessibility service, no WRITE_SETTINGS).
 */
class RotationService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.rotatetoggle.STOP"
        private const val CHANNEL_ID = "rotate_toggle"
        private const val NOTIF_ID = 1
        private const val AUTO_HIDE_MS = 5000L

        /** True only while this process has a live service. A killed process resets it. */
        @Volatile
        var running = false

        fun start(ctx: Context) {
            ctx.getSharedPreferences("rt", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true).apply()
            ctx.startForegroundService(Intent(ctx, RotationService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.getSharedPreferences("rt", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", false).apply()
            ctx.stopService(Intent(ctx, RotationService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var dm: DisplayManager
    private lateinit var orientationListener: OrientationEventListener
    private val handler = Handler(Looper.getMainLooper())

    private var lockView: View? = null
    private lateinit var lockParams: WindowManager.LayoutParams
    private var button: ImageButton? = null

    /** Surface.ROTATION_* that matches how the user is physically holding the phone. */
    private var targetRotation: Int? = null
    private var dismissed = false

    private val autoHide = Runnable {
        dismissed = true
        hideButton()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = evaluate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("rt", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (lockView == null) setUp()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rotate toggle", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RotationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rotate)
            .setContentTitle("Rotate toggle is running")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setUp() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // Invisible 1x1 window. Its screenOrientation is what actually rotates the screen.
        lockParams = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        lockView = View(this).also { wm.addView(it, lockParams) }

        dm.registerDisplayListener(displayListener, handler)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // Dead zones between orientations (and upside-down) are ignored = hysteresis.
                val zone = when {
                    degrees <= 40 || degrees >= 320 -> Surface.ROTATION_0
                    degrees in 50..130 -> Surface.ROTATION_270 // left side up -> reverse landscape
                    degrees in 230..310 -> Surface.ROTATION_90 // right side up -> landscape
                    else -> return
                }
                if (zone == targetRotation) return
                targetRotation = zone
                dismissed = false
                evaluate()
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()

        running = true
        refreshTile()
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(
                this, ComponentName(this, RotationTileService::class.java)
            )
        }
    }

    private fun currentRotation(): Int =
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private fun autoRotateOn(): Boolean =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    private fun evaluate() {
        // If the system's own auto-rotate is on, there's nothing for us to do.
        if (autoRotateOn()) {
            setLock(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            hideButton()
            return
        }
        val target = targetRotation
        if (target == null || target == currentRotation()) {
            hideButton()
        } else if (!dismissed) {
            showButton()
        }
    }

    private fun showButton() {
        if (button != null) return
        val size = dp(48)
        val b = ImageButton(this).apply {
            setImageResource(R.drawable.ic_rotate)
            setBackgroundResource(R.drawable.bg_rotate_button)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Rotate screen"
            setOnClickListener { onTap() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(24) // raise this if it overlaps your navigation bar
        }
        try {
            wm.addView(b, lp)
            button = b
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, AUTO_HIDE_MS)
        } catch (e: Exception) {
            button = null
        }
    }

    private fun hideButton() {
        handler.removeCallbacks(autoHide)
        button?.let { runCatching { wm.removeView(it) } }
        button = null
    }

    private fun onTap() {
        val orientation = when (targetRotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            // Back to portrait: release the lock so the system default applies again.
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setLock(orientation)
        hideButton()
    }

    private fun setLock(orientation: Int) {
        val v = lockView ?: return
        if (lockParams.screenOrientation == orientation) return
        lockParams.screenOrientation = orientation
        runCatching { wm.updateViewLayout(v, lockParams) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        refreshTile()
        handler.removeCallbacksAndMessages(null)
        if (::orientationListener.isInitialized) orientationListener.disable()
        if (::dm.isInitialized) dm.unregisterDisplayListener(displayListener)
        hideButton()
        lockView?.let { runCatching { wm.removeView(it) } }
        lockView = null
        super.onDestroy()
    }
}
