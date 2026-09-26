package com.yuri.corridaideal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
 * Motor v0.6.0.
 *
 * Não usa MediaProjection/gravação de tela. A leitura é feita sob demanda pelo
 * AccessibilityService.takeScreenshot(), que continua ligado quando o usuário
 * alterna entre Corrida Ideal e Uber. O serviço só recebe eventos do pacote
 * com.ubercab.driver e só tira screenshot quando o motorista toca na bolha.
 */
class UberAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var pill: LinearLayout? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val processing = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queuedSpeech: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        RuntimeState.settings = loadSettings()
        RuntimeState.captureReady = true
        RuntimeState.captureStatus = "LEITURA ATIVA — abra a Uber"
        RuntimeState.notifyChanged()
        createBubbleIfNeeded()
        root?.visibility = View.GONE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != UBER_PACKAGE) return
        createBubbleIfNeeded()
        root?.visibility = View.VISIBLE
        if (!processing.get() && RuntimeState.captureStatus.startsWith("LEITURA ATIVA")) {
            RuntimeState.captureStatus = "PRONTO"
            RuntimeState.notifyChanged()
            render()
        }
    }

    override fun onInterrupt() {
        RuntimeState.captureStatus = "Serviço interrompido"
        RuntimeState.notifyChanged()
    }

    private fun analyzeCurrentUberScreen() {
        if (processing.get()) return
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg != UBER_PACKAGE) {
            setStatus("ABRA A UBER")
            speak("Abra a Uber")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // O aparelho do projeto é Android moderno. Mantemos fallback textual
            // apenas para não quebrar em aparelhos antigos.
            parseFallbackTree()
            return
        }

        processing.set(true)
        setStatus("LENDO")
        root?.visibility = View.INVISIBLE

        mainExecutor.execute {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val buffer = screenshot.hardwareBuffer
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()
                                hardwareBitmap?.recycle()
                                if (bitmap == null) {
                                    finishFailure("TENTAR", "Não consegui ler")
                                } else {
                                    runOcr(bitmap)
                                }
                            } catch (_: Throwable) {
                                finishFailure("TENTAR", "Não consegui ler")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            // Se o sistema negar uma captura pontual, tenta o texto
                            // acessível da janela antes de desistir.
                            val parsed = parseTreeToOffer()
                            if (parsed?.ride != null) {
                                applyParsedOffer(parsed)
                            } else {
                                val msg = when (errorCode) {
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "AGUARDE"
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "TELA PROTEGIDA"
                                    else -> "TENTAR"
                                }
                                finishFailure(msg, "Não consegui ler")
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                finishFailure("TENTAR", "Não consegui ler")
            }
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                RuntimeState.lastOcrText = result.text
                val parsed = ScreenOfferParser.parse(result.text)
                if (parsed.ride != null) applyParsedOffer(parsed)
                else {
                    val fallback = parseTreeToOffer()
                    if (fallback?.ride != null) applyParsedOffer(fallback)
                    else finishFailure("NÃO LEU", "Não consegui ler")
                }
            }
            .addOnFailureListener {
                val fallback = parseTreeToOffer()
                if (fallback?.ride != null) applyParsedOffer(fallback)
                else finishFailure("TENTAR", "Não consegui ler")
            }
            .addOnCompleteListener { runCatching { bitmap.recycle() } }
    }

    private fun applyParsedOffer(parsed: ParsedScreenOffer) {
        val ride = parsed.ride ?: return finishFailure("NÃO LEU", "Não consegui ler")
        RuntimeState.settings = loadSettings()
        RuntimeState.captureConfidence = parsed.confidence
        RuntimeState.ride = ride
        RuntimeState.manualConsumptionOverrideKml = null
        RuntimeState.recalculate()
        val analysis = RuntimeState.analysis ?: return finishFailure("NÃO LEU", "Não consegui ler")
        runCatching { RideHistoryStore.onOfferAnalyzed(this, ride, analysis) }
        RuntimeState.captureStatus = when (analysis.grade) {
            RideGrade.GREEN -> "BOA"
            RideGrade.YELLOW -> "RAZOÁVEL"
            RideGrade.RED -> "RUIM"
        }
        RuntimeState.notifyChanged()
        processing.set(false)
        root?.visibility = View.VISIBLE
        render()
        speakGrade(analysis.grade)
    }

    private fun parseFallbackTree() {
        processing.set(true)
        setStatus("LENDO")
        val parsed = parseTreeToOffer()
        if (parsed?.ride != null) applyParsedOffer(parsed)
        else finishFailure("NÃO LEU", "Não consegui ler")
    }

    private fun parseTreeToOffer(): ParsedScreenOffer? {
        val node = rootInActiveWindow ?: return null
        if (node.packageName?.toString() != UBER_PACKAGE) return null
        val parts = ArrayList<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || parts.size > 500) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        if (parts.isEmpty()) return null
        val raw = parts.distinct().joinToString("\n")
        RuntimeState.lastOcrText = raw
        return ScreenOfferParser.parse(raw)
    }

    private fun finishFailure(status: String, voice: String? = null) {
        processing.set(false)
        RuntimeState.captureStatus = status
        RuntimeState.captureConfidence = 0
        RuntimeState.notifyChanged()
        root?.visibility = View.VISIBLE
        render()
        voice?.let(::speak)
    }

    private fun setStatus(status: String) {
        RuntimeState.captureStatus = status
        RuntimeState.notifyChanged()
        render()
    }

    private fun createBubbleIfNeeded() {
        if (root != null) return
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
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            render()
        } catch (_: Throwable) {
            root = null
            pill = null
            label = null
            params = null
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
                        if (!moved) analyzeCurrentUberScreen()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun render() {
        val l = label ?: return
        val b = pill ?: return
        val (text, color) = when (RuntimeState.captureStatus) {
            "LENDO" -> "LENDO…" to Color.rgb(42, 92, 145)
            "BOA" -> "BOA" to Color.rgb(22, 155, 75)
            "RAZOÁVEL" -> "RAZOÁVEL" to Color.rgb(203, 151, 20)
            "RUIM" -> "RUIM" to Color.rgb(194, 55, 55)
            "NÃO LEU", "TENTAR", "AGUARDE", "TELA PROTEGIDA" -> "TENTAR" to Color.rgb(120, 86, 32)
            else -> "ANALISAR" to Color.rgb(42, 92, 145)
        }
        l.text = text
        b.background = rounded(color, dp(30))
    }

    private fun speakGrade(grade: RideGrade) = speak(
        when (grade) {
            RideGrade.GREEN -> "Corrida boa"
            RideGrade.YELLOW -> "Corrida razoável"
            RideGrade.RED -> "Corrida ruim"
        }
    )

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

    private fun loadSettings(): AppSettings {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        return AppSettings(
            fuelPrice = p.getFloat("fuel", 6.88f).toDouble(),
            baseConsumptionKml = p.getFloat("base", 12.6f).toDouble(),
            currentConsumptionKml = p.getFloat("base", 12.6f).toDouble(),
            bestRealisticConsumptionKml = p.getFloat("best", 13.5f).toDouble(),
            targetNetPerKm = p.getFloat("netkm", 1.25f).toDouble(),
            targetNetPerHour = p.getFloat("neth", 35f).toDouble(),
            safetySpeedLimitKmh = p.getFloat("limit", 60f).toDouble(),
            tankCapacityLiters = p.getFloat("tank", 0f).toDouble()
        )
    }

    private fun removeBubble() {
        root?.let { r -> runCatching { windowManager?.removeView(r) } }
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
        if (instance === this) instance = null
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura desativada"
        RuntimeState.notifyChanged()
        removeBubble()
        runCatching { recognizer.close() }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val UBER_PACKAGE = "com.ubercab.driver"
        @Volatile var instance: UberAccessibilityService? = null
            private set
    }
}
