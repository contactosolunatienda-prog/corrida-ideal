package com.yuri.corridaideal

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)
    private var captureRequested = false
    private var autoEnabled = false
    private var lastAutoAt = 0L
    private var lastOfferSignature = ""
    private var tts: TextToSpeech? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_CAPTURE_ONCE -> {
                if (projection == null) {
                    RuntimeState.captureStatus = "Leitura de tela desligada"
                    RuntimeState.captureReady = false
                    RuntimeState.notifyChanged()
                } else {
                    captureRequested = true
                    RuntimeState.captureStatus = "Lendo oferta…"
                    RuntimeState.notifyChanged()
                }
            }
            ACTION_AUTO_ON -> {
                autoEnabled = true
                RuntimeState.autoCaptureEnabled = true
                RuntimeState.captureStatus = "AUTO ativo — aguardando oferta"
                RuntimeState.notifyChanged()
            }
            ACTION_AUTO_OFF -> {
                autoEnabled = false
                RuntimeState.autoCaptureEnabled = false
                RuntimeState.captureStatus = "Leitura de tela pronta"
                RuntimeState.notifyChanged()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startProjection(intent: Intent) {
        promoteForeground()
        stopProjectionOnly()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            RuntimeState.captureStatus = "Falha ao iniciar leitura de tela"
            RuntimeState.captureReady = false
            RuntimeState.notifyChanged()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, resultData)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    RuntimeState.captureReady = false
                    RuntimeState.autoCaptureEnabled = false
                    RuntimeState.captureStatus = "Leitura de tela encerrada"
                    RuntimeState.notifyChanged()
                    stopSelf()
                }
            }
        }, handler)

        val (width, height, density) = displayInfo()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = p.createVirtualDisplay(
            "CorridaIdealScreen",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            val shouldProcess = captureRequested || (autoEnabled && System.currentTimeMillis() - lastAutoAt >= AUTO_INTERVAL_MS)
            if (!shouldProcess || processing.get()) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            captureRequested = false
            lastAutoAt = System.currentTimeMillis()
            val plane = image.planes.firstOrNull()
            if (plane == null) { image.close(); return@setOnImageAvailableListener }
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = if (bmpWidth != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
            processBitmap(cropped)
        }, handler)

        RuntimeState.captureReady = true
        RuntimeState.captureStatus = "Leitura de tela pronta — toque em 📸 na Uber"
        RuntimeState.notifyChanged()
        speak("Leitura de tela pronta. Volte para a Uber. Quando chegar a oferta, toque no botão de câmera.")
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (!processing.compareAndSet(false, true)) return
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { text ->
                val raw = text.text
                val parsed = ScreenOfferParser.parse(raw)
                val screenState = ScreenStateDetector.detect(raw)
                RuntimeState.lastOcrText = raw
                RuntimeState.captureConfidence = parsed.confidence

                var lifecycleHandled = false
                when (screenState) {
                    UberScreenState.COMPLETED -> {
                        if (RideHistoryStore.activeRecord(this) != null) {
                            val completed = RideHistoryStore.completeActive(
                                this, RuntimeState.activeTripDistanceKm, RuntimeState.activeTripElapsedMinutes,
                                RuntimeState.activeTripAvgConsumptionKml, RuntimeState.settings
                            )
                            if (completed != null) {
                                RuntimeState.captureStatus = "Fim da corrida detectado — resultado salvo"
                                speak("Corrida finalizada e salva no relatório do dia.")
                                lifecycleHandled = true
                            }
                        }
                    }
                    UberScreenState.TO_PICKUP, UberScreenState.ON_TRIP -> {
                        if (RideHistoryStore.activeRecord(this) == null && RideHistoryStore.pendingOffer(this) != null) {
                            val accepted = RideHistoryStore.confirmAccepted(this, "ocr")
                            if (accepted != null) {
                                RuntimeState.captureStatus = "Aceite detectado automaticamente — corrida no relatório"
                                speak("Aceite detectado. Corrida adicionada ao relatório do dia.")
                                lifecycleHandled = true
                            }
                        }
                    }
                    else -> Unit
                }

                if (!lifecycleHandled && parsed.ride != null) {
                    val signature = "%.2f|%.2f|%.2f|%.0f".format(
                        parsed.ride.fare, parsed.ride.pickupKm, parsed.ride.rideKm, parsed.ride.estimatedMinutes
                    )
                    val changed = signature != lastOfferSignature
                    lastOfferSignature = signature
                    RuntimeState.ride = parsed.ride
                    RuntimeState.manualConsumptionOverrideKml = null
                    RuntimeState.recalculate()
                    RuntimeState.analysis?.let { RideHistoryStore.onOfferAnalyzed(this, parsed.ride, it) }
                    if (!autoEnabled) {
                        autoEnabled = true
                        RuntimeState.autoCaptureEnabled = true
                    }
                    RuntimeState.captureStatus = parsed.message + " • acompanhando aceite/fim"
                    RuntimeState.notifyChanged()
                    if (changed) speakAnalysis()
                } else {
                    if (!lifecycleHandled) RuntimeState.captureStatus = parsed.message
                    RuntimeState.notifyChanged()
                    if (!autoEnabled && !lifecycleHandled && screenState == UberScreenState.UNKNOWN) {
                        speak("Não consegui ler toda a oferta. Deixe a corrida visível e toque novamente.")
                    }
                }
            }
            .addOnFailureListener {
                RuntimeState.captureStatus = "Não consegui ler a tela. Tente novamente."
                RuntimeState.notifyChanged()
                if (!autoEnabled) speak("Não consegui ler a tela. Tente novamente.")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun speakAnalysis() {
        val a = RuntimeState.analysis ?: return
        val grade = when (a.grade) {
            RideGrade.GREEN -> "verde, vale a sua meta"
            RideGrade.YELLOW -> "amarela, depende"
            RideGrade.RED -> "vermelha, fora da sua meta"
        }
        val detail = buildString {
            append("Corrida $grade. ")
            append("%.2f reais brutos por quilômetro total. ".format(a.grossPerKm))
            append("%.2f reais líquidos por quilômetro. ".format(a.netPerKm))
            append("%.0f reais líquidos por hora. ".format(a.netPerHour))
            a.requiredConsumptionKml?.takeIf { it.isFinite() }?.let {
                append("Consumo alvo %.1f quilômetros por litro. ".format(it))
            }
            a.requiredAverageSpeedKmh?.takeIf { it.isFinite() }?.let {
                append("Média necessária perto de %.0f quilômetros por hora.".format(it))
            }
        }
        speak(detail)
    }

    private fun displayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), metrics.densityDpi)
        } else Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun promoteForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Corrida Ideal — leitura de tela")
            .setContentText("Capturas processadas no aparelho; nenhuma imagem é salva")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Leitura de ofertas", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopProjectionOnly() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    override fun onDestroy() {
        autoEnabled = false
        RuntimeState.captureReady = false
        RuntimeState.autoCaptureEnabled = false
        RuntimeState.captureStatus = "Leitura de tela desligada"
        RuntimeState.notifyChanged()
        stopProjectionOnly()
        recognizer.close()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screen-analysis")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("pt", "BR")
    }

    companion object {
        const val ACTION_START = "com.yuri.corridaideal.SCREEN_START"
        const val ACTION_CAPTURE_ONCE = "com.yuri.corridaideal.SCREEN_CAPTURE_ONCE"
        const val ACTION_AUTO_ON = "com.yuri.corridaideal.SCREEN_AUTO_ON"
        const val ACTION_AUTO_OFF = "com.yuri.corridaideal.SCREEN_AUTO_OFF"
        const val ACTION_STOP = "com.yuri.corridaideal.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL_ID = "corrida_ideal_screen"
        const val NOTIFICATION_ID = 45
        const val AUTO_INTERVAL_MS = 1200L
    }
}
