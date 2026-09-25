package com.yuri.corridaideal

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var pill: LinearLayout
    private lateinit var icon: ImageView
    private lateinit var label: TextView
    private lateinit var close: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val stateListener: (LiveSnapshot) -> Unit = { snap -> handler.post { render(snap) } }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        RuntimeState.addListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun createOverlay() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(14), 0)
            background = rounded(Color.rgb(42, 92, 145), dp(28))
        }
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_status_car)
            setColorFilter(Color.WHITE)
        }
        label = TextView(this).apply {
            text = "ANALISAR"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        pill.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) })
        pill.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        close = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(230, 45, 45, 45), dp(18))
            setOnClickListener { stopSelf() }
            contentDescription = "Esconder bolha"
        }

        root.addView(pill, LinearLayout.LayoutParams(dp(145), dp(56)))
        root.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) })

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10); y = dp(170)
        }

        installDragAndTap()
        windowManager.addView(root, params)
        render(RuntimeState.snapshot())
    }

    private fun installDragAndTap() {
        pill.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0
            var downX = 0f; var downY = 0f
            var moved = false
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        downX = event.rawX; downY = event.rawY; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (abs(dx) > dp(7) || abs(dy) > dp(7)) moved = true
                        if (moved) {
                            params.x = startX - dx.toInt(); params.y = startY + dy.toInt()
                            runCatching { windowManager.updateViewLayout(root, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) requestCapture()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun requestCapture() {
        if (!RuntimeState.captureReady) {
            label.text = "ATIVAR NO APP"
            pill.background = rounded(Color.rgb(90, 90, 90), dp(28))
            return
        }
        label.text = "LENDO…"
        pill.background = rounded(Color.rgb(42, 92, 145), dp(28))
        // Some Uber offer fields can sit under the floating pill. Hide it for a
        // fraction of a second so the screenshot contains only the underlying app.
        root.visibility = View.INVISIBLE
        handler.postDelayed({
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE))
            handler.postDelayed({ if (::root.isInitialized) root.visibility = View.VISIBLE }, 650L)
        }, 90L)
    }

    private fun render(s: LiveSnapshot) {
        val grade = s.analysis?.grade
        val statusIsGrade = s.captureStatus == "BOA" || s.captureStatus == "RAZOÁVEL" || s.captureStatus == "RUIM"
        if (!s.captureReady) {
            label.text = "ATIVAR NO APP"
            pill.background = rounded(Color.rgb(90, 90, 90), dp(28))
            return
        }
        if (!statusIsGrade) {
            label.text = if (s.captureStatus.startsWith("Lendo")) "LENDO…" else "ANALISAR"
            pill.background = rounded(Color.rgb(42, 92, 145), dp(28))
            return
        }
        when (grade) {
            RideGrade.GREEN -> { label.text = "BOA"; pill.background = rounded(Color.rgb(22, 155, 75), dp(28)) }
            RideGrade.YELLOW -> { label.text = "RAZOÁVEL"; pill.background = rounded(Color.rgb(203, 151, 20), dp(28)) }
            RideGrade.RED -> { label.text = "RUIM"; pill.background = rounded(Color.rgb(194, 55, 55), dp(28)) }
            null -> { label.text = "ANALISAR"; pill.background = rounded(Color.rgb(42, 92, 145), dp(28)) }
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); setStroke(dp(2), Color.argb(130, 255, 255, 255))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bolha Corrida Ideal", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun promoteForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_car)
            .setContentTitle("Corrida Ideal ativo")
            .setContentText("Bolha pronta para analisar ofertas")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        if (::root.isInitialized) runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val CHANNEL_ID = "corrida_ideal_overlay_v052"
        const val NOTIFICATION_ID = 44
    }
}
