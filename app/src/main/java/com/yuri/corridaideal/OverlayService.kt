package com.yuri.corridaideal

import android.app.Service
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

/**
 * v0.5.6: bolha separada da captura, como na arquitetura estável inicial.
 * O processo continua mantido vivo pelo ScreenCaptureService (mediaProjection FGS),
 * enquanto este serviço cuida apenas da UI flutuante.
 */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var pill: LinearLayout
    private lateinit var label: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val stateListener: (LiveSnapshot) -> Unit = { snap -> handler.post { render(snap) } }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        RuntimeState.addListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::root.isInitialized) {
            if (root.visibility != View.VISIBLE) root.visibility = View.VISIBLE
            render(RuntimeState.snapshot())
        }
        return START_NOT_STICKY
    }

    private fun createOverlay() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(15), 0)
            background = rounded(Color.rgb(42, 92, 145), dp(30))
        }

        val icon = ImageView(this).apply {
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
        pill.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(7) })
        pill.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(pill, LinearLayout.LayoutParams(dp(160), dp(60)))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(170)
        }

        installDragAndTap()
        windowManager.addView(root, params)
        render(RuntimeState.snapshot())
    }

    private fun installDragAndTap() {
        pill.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(7) || abs(dy) > dp(7)) moved = true
                        if (moved) {
                            params.x = startX - dx.toInt()
                            params.y = startY + dy.toInt()
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
            label.text = "REATIVAR"
            pill.background = rounded(Color.rgb(95, 95, 95), dp(30))
            return
        }

        label.text = "LENDO…"
        pill.background = rounded(Color.rgb(42, 92, 145), dp(30))
        root.visibility = View.INVISIBLE
        handler.postDelayed({
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE))
            handler.postDelayed({ if (::root.isInitialized) root.visibility = View.VISIBLE }, 650L)
        }, 90L)
    }

    private fun render(s: LiveSnapshot) {
        val (text, color) = when {
            !s.captureReady -> "REATIVAR" to Color.rgb(95, 95, 95)
            s.captureStatus == "LENDO" -> "LENDO…" to Color.rgb(42, 92, 145)
            s.captureStatus == "BOA" -> "BOA" to Color.rgb(22, 155, 75)
            s.captureStatus == "RAZOÁVEL" -> "RAZOÁVEL" to Color.rgb(203, 151, 20)
            s.captureStatus == "RUIM" -> "RUIM" to Color.rgb(194, 55, 55)
            s.captureStatus == "NÃO LEU" || s.captureStatus == "TENTAR" || s.captureStatus == "TENTE NOVAMENTE" -> "TENTAR" to Color.rgb(120, 86, 32)
            else -> "ANALISAR" to Color.rgb(42, 92, 145)
        }
        label.text = text
        pill.background = rounded(color, dp(30))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(2), Color.argb(135, 255, 255, 255))
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        if (::root.isInitialized) runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
