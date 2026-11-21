package com.example.assistant

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/** Cửa sổ nhỏ hiện trạng thái/thao tác của agent, có thể kéo đi. */
class AssistantOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        addOverlay()
    }

    @SuppressLint("InflateParams")
    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val text = TextView(this).apply {
            text = "Trợ lý: sẵn sàng"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 25, 28, 38))
            setPadding(20, 14, 20, 14)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        text.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(text, params)
            overlayView = text
            layoutParams = params
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Trợ lý ảo đang chạy")
        .setContentText("Nhấn để mở lại ứng dụng")
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Trợ lý ảo", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    fun updateText(value: String) {
        overlayView?.post {
            (overlayView as? TextView)?.text = "Trợ lý: $value"
        }
    }

    override fun onDestroy() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AssistantOverlay"
        private const val CHANNEL_ID = "assistant_overlay"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: AssistantOverlayService? = null
            private set

        fun show(text: String) {
            instance?.updateText(text)
        }

        fun start(context: Context) {
            val intent = Intent(context, AssistantOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
