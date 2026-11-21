package com.localassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class FloatingBubbleService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var expanded = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        val bubbleView = TextView(this).apply {
            text = "م"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "فتح المساعد العائم"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF087F8C.toInt())
                setStroke(dp(1), Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(180)
        }
        overlayParams = params
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        params.x = (startX - dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel()
                    true
                }
                else -> true
            }
        }
        bubble = bubbleView
        windowManager.addView(bubbleView, params)
    }

    private fun togglePanel() {
        if (expanded) {
            panel?.let { windowManager.removeView(it) }
            panel = null
            expanded = false
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(0xF5FFFFFF.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFCBD5E1.toInt())
            }
        }
        val title = TextView(this).apply {
            text = "مساعد محلي"
            textSize = 12f
            setTextColor(0xFF17212B.toInt())
            gravity = Gravity.CENTER
        }
        val selectedText = EditText(this).apply {
            hint = "الصق النص المحدد هنا"
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "نص لمشاركته أو قراءته"
        }
        column.addView(title, LinearLayout.LayoutParams(-1, dp(28)))
        column.addView(selectedText, LinearLayout.LayoutParams(-1, dp(40)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(smallButton("🎙", "فتح الميكروفون") { launchAssistant(startListening = true) }, weightedButtonParams())
        row.addView(smallButton("🔊", "قراءة النص") {
            val text = selectedText.text.toString().trim()
            if (text.isEmpty()) toast("ألصق النص الذي تريد قراءته.")
            else if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "floating-text")
            else toast("الصوت العربي غير جاهز على الجهاز.")
        }, weightedButtonParams())
        row.addView(smallButton("ع", "ترجمة النص") {
            val text = selectedText.text.toString().trim()
            if (text.isEmpty()) toast("ألصق النص المحدد أولاً.")
            else launchAssistant(text = text)
        }, weightedButtonParams())
        column.addView(row, LinearLayout.LayoutParams(-1, dp(46)))
        val close = smallButton("إغلاق", "إغلاق أدوات المساعد") { togglePanel() }.apply {
            setTextColor(0xFFB3261E.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFE9E7.toInt()))
        }
        column.addView(close, LinearLayout.LayoutParams(-1, dp(36)))

        if (overlayParams == null) return
        panel = column
        val panelParams = WindowManager.LayoutParams(
            dp(250),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(225)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(column, panelParams)
        expanded = true
    }

    private fun smallButton(label: String, description: String, click: () -> Unit) =
        Button(this).apply {
            text = label
            contentDescription = description
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF087F8C.toInt()))
            minimumHeight = dp(36)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { click() }
        }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }

    private fun launchAssistant(text: String? = null, startListening: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (text != null) putExtra("bubble_text", text)
            if (startListening) putExtra("start_listening", true)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "المساعد العائم", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("المساعد العائم يعمل")
            .setContentText("اضغط إيقاف لإغلاق الفقاعة")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stopIntent).build())
            .build()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ar"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        bubble?.let { runCatching { windowManager.removeView(it) } }
        tts?.stop()
        tts?.shutdown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.localassistant.STOP_BUBBLE"
    }
}
