package com.yuri.corridaideal

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Painel flutuante v1.0.0.
 *
 * Mantém o painel separado do MediaProjection, como na v0.2 que funcionou.
 * A captura só é solicitada quando o motorista toca ANALISAR.
 */
class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var header: TextView? = null
    private var action: TextView? = null
    private var detail: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private val stateListener: (LiveSnapshot) -> Unit = { snap -> handler.post { render(snap) } }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        RuntimeState.addListener(stateListener)
        createPanel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> root?.visibility = View.VISIBLE
        }
        return START_NOT_STICKY
    }

    private fun createPanel() {
        if (root != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = rounded(Color.argb(242, 25, 25, 25), dp(18), Color.argb(130, 255, 255, 255))
        }

        val title = TextView(this).apply {
            text = "🚗  CORRIDA IDEAL"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }

        val button = TextView(this).apply {
            text = "ATIVAR LEITURA"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(Color.rgb(70, 90, 115), dp(14), Color.argb(150, 255, 255, 255))
            setOnClickListener { onMainAction() }
        }

        val info = TextView(this).apply {
            text = "Abra a Uber e ative a leitura uma vez."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), 0)
        }

        container.addView(title, LinearLayout.LayoutParams(dp(270), dp(38)))
        container.addView(button, LinearLayout.LayoutParams(dp(270), dp(62)))
        container.addView(info, LinearLayout.LayoutParams(dp(270), dp(38)))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(145)
        }

        root = container
        header = title
        action = button
        detail = info
        params = lp
        installDrag(title)
        windowManager?.addView(container, lp)
        render(RuntimeState.snapshot())
    }

    private fun installDrag(target: View) {
        target.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val p = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                        if (moved) {
                            p.x = startX - dx.toInt()
                            p.y = startY + dy.toInt()
                            runCatching { windowManager?.updateViewLayout(root, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
    }

    private fun onMainAction() {
        if (!RuntimeState.captureReady) {
            RuntimeState.captureStatus = "PEDINDO AUTORIZAÇÃO"
            RuntimeState.notifyChanged()
            startActivity(
                Intent(this, CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val current = RuntimeState.captureStatus
        if (current == "LENDO") return

        RuntimeState.captureStatus = "LENDO"
        RuntimeState.notifyChanged()

        // Esconde o painel para não cobrir os números da oferta na captura.
        root?.visibility = View.INVISIBLE
        handler.postDelayed({
            val i = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE)
            startService(i)
            handler.postDelayed({ root?.visibility = View.VISIBLE }, 850L)
        }, 90L)
    }

    private fun render(s: LiveSnapshot) {
        val button = action ?: return
        val info = detail ?: return

        if (!s.captureReady) {
            button.text = "ATIVAR LEITURA"
            button.background = rounded(Color.rgb(70, 90, 115), dp(14), Color.argb(150, 255, 255, 255))
            info.text = "Faça isso já com a Uber aberta. Depois só ANALISAR."
            return
        }

        when (s.captureStatus) {
            "LENDO" -> {
                button.text = "LENDO…"
                button.background = rounded(Color.rgb(42, 92, 145), dp(14), Color.argb(150, 255, 255, 255))
                info.text = "Aguarde um instante"
            }
            "BOA" -> {
                button.text = "BOA"
                button.background = rounded(Color.rgb(22, 155, 75), dp(14), Color.argb(150, 255, 255, 255))
                info.text = metrics(s)
            }
            "RAZOÁVEL" -> {
                button.text = "RAZOÁVEL"
                button.background = rounded(Color.rgb(203, 151, 20), dp(14), Color.argb(150, 255, 255, 255))
                info.text = metrics(s)
            }
            "RUIM" -> {
                button.text = "RUIM"
                button.background = rounded(Color.rgb(194, 55, 55), dp(14), Color.argb(150, 255, 255, 255))
                info.text = metrics(s)
            }
            "NÃO LEU", "TENTAR" -> {
                button.text = "TENTAR DE NOVO"
                button.background = rounded(Color.rgb(120, 86, 32), dp(14), Color.argb(150, 255, 255, 255))
                info.text = "Oferta visível? Toque novamente."
            }
            else -> {
                button.text = "ANALISAR"
                button.background = rounded(Color.rgb(42, 92, 145), dp(14), Color.argb(150, 255, 255, 255))
                info.text = "Oferta apareceu? Toque uma vez."
            }
        }
    }

    private fun metrics(s: LiveSnapshot): String {
        val a = s.analysis ?: return "Resultado pronto"
        return "Após gasolina: R$ %.2f/km  •  R$ %.0f/h".format(a.netPerKm, a.netPerHour)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Corrida Ideal — painel", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun promoteForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_car)
            .setContentTitle("Corrida Ideal — painel ativo")
            .setContentText("Abra a Uber e use a janela flutuante")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        action = null
        detail = null
        header = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_STOP = "com.yuri.corridaideal.OVERLAY_STOP"
        const val ACTION_SHOW = "com.yuri.corridaideal.OVERLAY_SHOW"
        const val CHANNEL_ID = "corrida_ideal_panel_v1"
        const val NOTIFICATION_ID = 44
    }
}
