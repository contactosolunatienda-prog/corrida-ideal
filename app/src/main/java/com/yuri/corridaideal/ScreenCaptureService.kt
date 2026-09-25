package com.yuri.corridaideal

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v0.5.3: captura + bolha ficam no MESMO serviço em primeiro plano.
 * Isso elimina a disputa entre dois serviços e evita a bolha sumir quando o Android
 * encerra o antigo OverlayService.
 */
class ScreenCaptureService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)
    @Volatile private var captureRequested = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queuedSpeech: String? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var pill: LinearLayout? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val stateListener: (LiveSnapshot) -> Unit = { snap -> handler.post { render(snap) } }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        RuntimeState.addListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_CAPTURE_ONCE -> requestCapture()
            ACTION_SHOW_BUBBLE -> ensureOverlayVisible()
            ACTION_HIDE_BUBBLE -> root?.visibility = View.GONE
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // A autorização do MediaProjection não pode ser recriada silenciosamente depois
        // que o processo morre, então não tentamos reiniciar o serviço sem consentimento.
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            failSession("Autorização de leitura inválida")
            return
        }

        try {
            promoteForeground()
            ensureOverlayVisible()

            if (projection == null) {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = manager.getMediaProjection(resultCode, resultData)
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handler.post {
                            cleanupProjection(callStop = false)
                            RuntimeState.captureReady = false
                            RuntimeState.captureStatus = "REATIVAR"
                            RuntimeState.notifyChanged()
                            render(RuntimeState.snapshot())
                        }
                    }
                }, handler)

                val (width, height, density) = displayInfo()
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                virtualDisplay = p.createVirtualDisplay(
                    "CorridaIdealSession",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    handler
                )

                imageReader!!.setOnImageAvailableListener({ reader -> onImageAvailable(reader, width, height) }, handler)
            }

            RuntimeState.captureReady = true
            RuntimeState.captureConfidence = 0
            RuntimeState.captureStatus = "PRONTO"
            RuntimeState.notifyChanged()
            render(RuntimeState.snapshot())
        } catch (t: Throwable) {
            failSession("Falha ao iniciar leitura")
        }
    }

    private fun requestCapture() {
        if (projection == null || imageReader == null || !RuntimeState.captureReady) {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "REATIVAR"
            RuntimeState.notifyChanged()
            render(RuntimeState.snapshot())
            return
        }
        if (processing.get() || captureRequested) return

        RuntimeState.captureStatus = "LENDO"
        RuntimeState.notifyChanged()
        render(RuntimeState.snapshot())

        // Esconde a própria bolha antes da captura para não encobrir números da oferta.
        root?.visibility = View.INVISIBLE
        handler.postDelayed({
            captureRequested = true
            // Caso a tela não gere um frame imediatamente, mostre a bolha novamente.
            handler.postDelayed({ root?.visibility = View.VISIBLE }, 450L)
        }, 120L)
    }

    private fun onImageAvailable(reader: ImageReader, width: Int, height: Int) {
        if (!captureRequested || processing.get()) {
            reader.acquireLatestImage()?.close()
            return
        }

        val image = reader.acquireLatestImage() ?: return
        captureRequested = false
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            val plane = image.planes.firstOrNull() ?: throw IllegalStateException("Sem plano de imagem")
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = if (bmpWidth != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
            if (cropped !== bitmap) bitmap.recycle()
            processBitmap(cropped)
        } catch (t: Throwable) {
            runCatching { image.close() }
            processing.set(false)
            root?.visibility = View.VISIBLE
            RuntimeState.captureStatus = "TENTE NOVAMENTE"
            RuntimeState.notifyChanged()
            render(RuntimeState.snapshot())
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val raw = result.text
                    RuntimeState.lastOcrText = raw
                    val parsed = ScreenOfferParser.parse(raw)
                    RuntimeState.captureConfidence = parsed.confidence
                    val ride = parsed.ride

                    if (ride == null) {
                        RuntimeState.captureStatus = "NÃO LEU"
                        RuntimeState.notifyChanged()
                        speak("Não consegui ler")
                        return@addOnSuccessListener
                    }

                    RuntimeState.ride = ride
                    RuntimeState.manualConsumptionOverrideKml = null
                    RuntimeState.recalculate()
                    val analysis = RuntimeState.analysis
                    if (analysis == null) {
                        RuntimeState.captureStatus = "NÃO LEU"
                        RuntimeState.notifyChanged()
                        return@addOnSuccessListener
                    }

                    runCatching { RideHistoryStore.onOfferAnalyzed(this, ride, analysis) }
                    RuntimeState.captureStatus = when (analysis.grade) {
                        RideGrade.GREEN -> "BOA"
                        RideGrade.YELLOW -> "RAZOÁVEL"
                        RideGrade.RED -> "RUIM"
                    }
                    RuntimeState.notifyChanged()
                    speakGrade(analysis.grade)
                }
                .addOnFailureListener {
                    RuntimeState.captureStatus = "NÃO LEU"
                    RuntimeState.notifyChanged()
                    speak("Não consegui ler")
                }
                .addOnCompleteListener {
                    runCatching { bitmap.recycle() }
                    processing.set(false)
                    root?.visibility = View.VISIBLE
                    render(RuntimeState.snapshot())
                }
        } catch (t: Throwable) {
            runCatching { bitmap.recycle() }
            processing.set(false)
            root?.visibility = View.VISIBLE
            RuntimeState.captureStatus = "TENTE NOVAMENTE"
            RuntimeState.notifyChanged()
            render(RuntimeState.snapshot())
        }
    }

    private fun ensureOverlayVisible() {
        if (!Settings.canDrawOverlays(this)) {
            RuntimeState.captureStatus = "Permita exibir sobre outros apps"
            RuntimeState.notifyChanged()
            return
        }
        if (root != null) {
            root?.visibility = View.VISIBLE
            render(RuntimeState.snapshot())
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(15), 0)
                background = rounded(Color.rgb(42, 92, 145), dp(30))
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_status_car)
                setColorFilter(Color.WHITE)
            }
            val text = TextView(this).apply {
                this.text = "ANALISAR"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            button.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(7) })
            button.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            container.addView(button, LinearLayout.LayoutParams(dp(160), dp(60)))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(10)
                y = dp(170)
            }

            root = container
            pill = button
            label = text
            params = lp
            installDragAndTap(button)
            windowManager?.addView(container, lp)
            render(RuntimeState.snapshot())
        } catch (t: Throwable) {
            root = null
            pill = null
            label = null
            params = null
            RuntimeState.captureStatus = "Falha ao mostrar bolha"
            RuntimeState.notifyChanged()
        }
    }

    private fun installDragAndTap(target: View) {
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
                        if (abs(dx) > dp(7) || abs(dy) > dp(7)) moved = true
                        if (moved) {
                            p.x = startX - dx.toInt()
                            p.y = startY + dy.toInt()
                            runCatching { windowManager?.updateViewLayout(root, p) }
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

    private fun render(s: LiveSnapshot) {
        val l = label ?: return
        val b = pill ?: return
        val (text, color) = when {
            !s.captureReady || s.captureStatus == "REATIVAR" -> "REATIVAR" to Color.rgb(95, 95, 95)
            s.captureStatus == "LENDO" -> "LENDO…" to Color.rgb(42, 92, 145)
            s.captureStatus == "BOA" -> "BOA" to Color.rgb(22, 155, 75)
            s.captureStatus == "RAZOÁVEL" -> "RAZOÁVEL" to Color.rgb(203, 151, 20)
            s.captureStatus == "RUIM" -> "RUIM" to Color.rgb(194, 55, 55)
            s.captureStatus == "NÃO LEU" || s.captureStatus == "TENTE NOVAMENTE" -> "TENTAR" to Color.rgb(120, 86, 32)
            else -> "ANALISAR" to Color.rgb(42, 92, 145)
        }
        l.text = text
        b.background = rounded(color, dp(30))
    }

    private fun speakGrade(grade: RideGrade) = speak(when (grade) {
        RideGrade.GREEN -> "Corrida boa"
        RideGrade.YELLOW -> "Corrida razoável"
        RideGrade.RED -> "Corrida ruim"
    })

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "corrida-grade")
        else queuedSpeech = text
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("pt", "BR")
            queuedSpeech?.let {
                tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "corrida-grade")
                queuedSpeech = null
            }
        }
    }

    private fun promoteForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_car)
            .setContentTitle("Corrida Ideal — turno ativo")
            .setContentText("Bolha pronta para analisar ofertas")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar turno", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Corrida Ideal", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun displayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), metrics.densityDpi)
        } else {
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun failSession(message: String) {
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = message
        RuntimeState.notifyChanged()
        render(RuntimeState.snapshot())
    }

    private fun cleanupProjection(callStop: Boolean = true) {
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        val p = projection
        virtualDisplay = null
        imageReader = null
        projection = null
        if (callStop) runCatching { p?.stop() }
    }

    private fun removeOverlay() {
        val r = root
        if (r != null) runCatching { windowManager?.removeView(r) }
        root = null
        pill = null
        label = null
        params = null
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(2), Color.argb(135, 255, 255, 255))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura desligada"
        RuntimeState.notifyChanged()
        cleanupProjection(callStop = true)
        removeOverlay()
        runCatching { recognizer.close() }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yuri.corridaideal.SCREEN_START"
        const val ACTION_CAPTURE_ONCE = "com.yuri.corridaideal.SCREEN_CAPTURE_ONCE"
        const val ACTION_SHOW_BUBBLE = "com.yuri.corridaideal.SCREEN_SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.yuri.corridaideal.SCREEN_HIDE_BUBBLE"
        const val ACTION_STOP = "com.yuri.corridaideal.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL_ID = "corrida_ideal_v053"
        const val NOTIFICATION_ID = 45
    }
}
