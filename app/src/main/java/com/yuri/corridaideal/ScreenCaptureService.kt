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

/**
 * v0.5.6: volta ao núcleo de captura da versão 0.2, que já funcionou no aparelho.
 * A captura fica em um FGS mediaProjection independente da bolha.
 */
class ScreenCaptureService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)
    @Volatile private var captureRequested = false
    private var captureTimeout: Runnable? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queuedSpeech: String? = null
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
            ACTION_CAPTURE_ONCE -> requestCapture()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        promoteForeground()
        cleanupProjection(callStop = true)

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
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = manager.getMediaProjection(resultCode, resultData)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post {
                        captureTimeout?.let { handler.removeCallbacks(it) }
                        captureTimeout = null
                        captureRequested = false
                        cleanupProjection(callStop = false)
                        RuntimeState.captureReady = false
                        RuntimeState.captureStatus = "REATIVAR"
                        RuntimeState.notifyChanged()
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
            imageReader!!.setOnImageAvailableListener({ reader -> onImageAvailable(reader, width, height) }, handler)

            RuntimeState.captureReady = true
            RuntimeState.captureConfidence = 0
            RuntimeState.captureStatus = "PRONTO"
            RuntimeState.notifyChanged()

        } catch (t: Throwable) {
            failSession("Falha ao iniciar leitura")
        }
    }

    private fun requestCapture() {
        if (projection == null || imageReader == null || !RuntimeState.captureReady) {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "REATIVAR"
            RuntimeState.notifyChanged()
            return
        }
        if (processing.get() || captureRequested) return

        captureRequested = true
        RuntimeState.captureStatus = "LENDO"
        RuntimeState.notifyChanged()

        captureTimeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (captureRequested && !processing.get()) {
                captureRequested = false
                RuntimeState.captureStatus = "TENTAR"
                RuntimeState.notifyChanged()
            }
        }
        captureTimeout = timeout
        handler.postDelayed(timeout, 1800L)
    }

    private fun onImageAvailable(reader: ImageReader, width: Int, height: Int) {
        if (!captureRequested || processing.get()) {
            reader.acquireLatestImage()?.close()
            return
        }

        val image = reader.acquireLatestImage() ?: return
        captureRequested = false
        captureTimeout?.let { handler.removeCallbacks(it) }
        captureTimeout = null

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
            RuntimeState.captureStatus = "TENTE NOVAMENTE"
            RuntimeState.notifyChanged()
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
                }
        } catch (t: Throwable) {
            runCatching { bitmap.recycle() }
            processing.set(false)
            RuntimeState.captureStatus = "TENTE NOVAMENTE"
            RuntimeState.notifyChanged()
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
            .setContentText("Leitura pronta; use a bolha sobre a Uber")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar turno", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
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
        } else Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun failSession(message: String) {
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = message
        RuntimeState.notifyChanged()
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

    override fun onDestroy() {
        captureTimeout?.let { handler.removeCallbacks(it) }
        captureTimeout = null
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura desligada"
        RuntimeState.notifyChanged()
        cleanupProjection(callStop = true)
        stopService(Intent(this, OverlayService::class.java))
        runCatching { recognizer.close() }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yuri.corridaideal.SCREEN_START"
        const val ACTION_CAPTURE_ONCE = "com.yuri.corridaideal.SCREEN_CAPTURE_ONCE"
        const val ACTION_STOP = "com.yuri.corridaideal.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL_ID = "corrida_ideal_v056"
        const val NOTIFICATION_ID = 45
    }
}
