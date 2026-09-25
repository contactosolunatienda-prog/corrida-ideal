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
    @Volatile private var captureRequested = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queuedSpeech: String? = null
    private var lastSignature = ""
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
                    RuntimeState.captureReady = false
                    RuntimeState.captureStatus = "Sessão encerrada — ative novamente no Corrida Ideal"
                    RuntimeState.notifyChanged()
                } else if (!processing.get()) {
                    captureRequested = true
                    RuntimeState.captureStatus = "Lendo oferta…"
                    RuntimeState.notifyChanged()
                }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        promoteForeground()
        if (projection != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "Falha ao iniciar leitura"
            RuntimeState.notifyChanged()
            stopSelf()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, resultData)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    RuntimeState.captureReady = false
                    RuntimeState.captureStatus = "Sessão de leitura encerrada — abra Corrida Ideal para ativar novamente"
                    RuntimeState.notifyChanged()
                    cleanupProjection(callStop = false)
                    stopSelf()
                }
            }
        }, handler)

        val (width, height, density) = displayInfo()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = p.createVirtualDisplay(
            "CorridaIdealSession",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!captureRequested || processing.get()) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            captureRequested = false
            if (!processing.compareAndSet(false, true)) { image.close(); return@setOnImageAvailableListener }

            val plane = image.planes.firstOrNull()
            if (plane == null) {
                image.close(); processing.set(false); return@setOnImageAvailableListener
            }
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
        }, handler)

        RuntimeState.captureReady = true
        RuntimeState.captureStatus = "Pronto — abra a Uber e toque em ANALISAR"
        RuntimeState.captureConfidence = 0
        RuntimeState.notifyChanged()
    }

    private fun processBitmap(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val raw = result.text
                RuntimeState.lastOcrText = raw

                // Uma captura também pode registrar aceite/fim no relatório, mas nunca fica
                // fazendo OCR em loop. Tudo acontece somente quando o motorista toca na bolha.
                when (ScreenStateDetector.detect(raw)) {
                    UberScreenState.TO_PICKUP, UberScreenState.ON_TRIP -> {
                        runCatching {
                            if (RideHistoryStore.activeRecord(this) == null && RideHistoryStore.pendingOffer(this) != null) {
                                RideHistoryStore.confirmAccepted(this, "captura")
                            }
                        }
                    }
                    UberScreenState.COMPLETED -> {
                        runCatching {
                            if (RideHistoryStore.activeRecord(this) != null) {
                                RideHistoryStore.completeActive(
                                    this,
                                    actualDistanceKm = 0.0,
                                    actualMinutes = 0.0,
                                    averageConsumptionKml = RuntimeState.obdConsumptionKml ?: RuntimeState.settings.baseConsumptionKml,
                                    settings = RuntimeState.settings
                                )
                            }
                        }
                    }
                    else -> Unit
                }

                val parsed = ScreenOfferParser.parse(raw)
                RuntimeState.captureConfidence = parsed.confidence
                val ride = parsed.ride
                if (ride == null) {
                    RuntimeState.captureStatus = "Não consegui ler — toque novamente com a oferta visível"
                    RuntimeState.notifyChanged()
                    speak("Não consegui ler")
                    return@addOnSuccessListener
                }

                RuntimeState.ride = ride
                RuntimeState.manualConsumptionOverrideKml = null
                RuntimeState.recalculate()
                val analysis = RuntimeState.analysis
                if (analysis == null) {
                    RuntimeState.captureStatus = "Não consegui calcular"
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

                val signature = "%.2f|%.2f|%.2f|%.0f".format(ride.fare, ride.pickupKm, ride.rideKm, ride.estimatedMinutes)
                if (signature != lastSignature) {
                    lastSignature = signature
                    speakGrade(analysis.grade)
                } else {
                    // Mesmo repetindo a mesma oferta após um novo toque, dê confirmação curta.
                    speakGrade(analysis.grade)
                }
            }
            .addOnFailureListener {
                RuntimeState.captureStatus = "Falha na leitura — tente novamente"
                RuntimeState.notifyChanged()
                speak("Não consegui ler")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                processing.set(false)
            }
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
            .setSmallIcon(R.drawable.ic_status_car)
            .setContentTitle("Corrida Ideal — turno ativo")
            .setContentText("A bolha lê somente quando você toca em ANALISAR")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Leitura das ofertas", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun cleanupProjection(callStop: Boolean = true) {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        val p = projection
        virtualDisplay = null
        imageReader = null
        projection = null
        if (callStop) runCatching { p?.stop() }
    }

    override fun onDestroy() {
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura de tela desligada"
        RuntimeState.notifyChanged()
        cleanupProjection(callStop = true)
        runCatching { recognizer.close() }
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yuri.corridaideal.SCREEN_START"
        const val ACTION_CAPTURE_ONCE = "com.yuri.corridaideal.SCREEN_CAPTURE_ONCE"
        const val ACTION_STOP = "com.yuri.corridaideal.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL_ID = "corrida_ideal_screen_v052"
        const val NOTIFICATION_ID = 45
    }
}
