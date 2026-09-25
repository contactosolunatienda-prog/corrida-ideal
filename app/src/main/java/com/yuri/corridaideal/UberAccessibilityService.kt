package com.yuri.corridaideal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Leitura leve da tela da Uber.
 *
 * Regra da v0.5.1:
 * - eventos de acessibilidade usam APENAS o texto já exposto pela Uber;
 * - OCR por screenshot só roda quando o motorista toca em ANALISAR e a leitura de texto falha;
 * - nunca abre MediaProjection/compartilhamento de tela;
 * - nunca aceita ou recusa corrida.
 */
class UberAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }

    private var recognizer: TextRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private var windowManager: WindowManager? = null
    private var overlayRoot: LinearLayout? = null
    private var statusPill: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false

    private var lastSpokenSignature = ""
    private var lastRawFingerprint = 0
    private var lastAutoAnalysisAt = 0L
    private var lastScreenshotAt = 0L
    private var screenshotBusy = false
    private var analyzeRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        loadSettings()
        tts = TextToSpeech(this, this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        RuntimeState.captureReady = true
        RuntimeState.autoCaptureEnabled = true
        RuntimeState.captureStatus = "Leitura automática ativa — abra o Uber Driver"
        RuntimeState.notifyChanged()
        createOverlayIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != UBER_PACKAGE) return

        if (!isBubbleHidden()) showOverlayInternal()

        // A Uber dispara muitos eventos seguidos. Não percorremos a árvore em cada
        // evento: aguardamos a tela estabilizar e fazemos UMA leitura leve.
        analyzeRunnable?.let(handler::removeCallbacks)
        analyzeRunnable = Runnable {
            val raw = currentUberText() ?: return@Runnable
            val fingerprint = raw.hashCode()
            val now = System.currentTimeMillis()
            if (fingerprint == lastRawFingerprint && now - lastAutoAnalysisAt < AUTO_REPEAT_GUARD_MS) {
                return@Runnable
            }
            lastRawFingerprint = fingerprint
            lastAutoAnalysisAt = now
            runCatching { processRawText(raw, manual = false, source = "acessibilidade") }
        }
        handler.postDelayed(analyzeRunnable!!, AUTO_DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        RuntimeState.captureStatus = "Leitura automática temporariamente interrompida"
        RuntimeState.notifyChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        analyzeRunnable?.let(handler::removeCallbacks)
        recognizer?.close()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        removeOverlay()
        RuntimeState.captureReady = false
        RuntimeState.autoCaptureEnabled = false
        RuntimeState.captureStatus = "Leitura automática desligada"
        RuntimeState.notifyChanged()
        super.onDestroy()
    }

    fun showBubbleFromApp() {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putBoolean(KEY_HIDE_BUBBLE, false).apply()
        createOverlayIfNeeded()
        showOverlayInternal()
    }

    fun hideBubbleUntilAppReopen() {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putBoolean(KEY_HIDE_BUBBLE, true).apply()
        overlayRoot?.visibility = View.GONE
    }

    fun analyzeNowFromApp() {
        showBubbleFromApp()
        analyzeCurrentScreen(manual = true)
    }

    private fun currentUberText(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != UBER_PACKAGE) return null
        return collectVisibleText(root).takeIf { it.isNotBlank() }
    }

    private fun analyzeCurrentScreen(manual: Boolean) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != UBER_PACKAGE) {
            if (manual) {
                showNeutral("ABRA A UBER")
                speakSimple("Abra a Uber")
            }
            return
        }

        val raw = collectVisibleText(root)
        if (raw.isNotBlank()) {
            val handled = runCatching { processRawText(raw, manual, source = "acessibilidade") }.getOrDefault(false)
            if (handled) return
        }

        // Screenshot via Accessibility não pede compartilhamento de tela.
        // Só é usado no toque manual, nunca em loop automático.
        if (manual && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestScreenshotOcr()
        } else if (manual) {
            showNeutral("NÃO LI")
            speakSimple("Não consegui ler a oferta")
        }
    }

    private fun processRawText(raw: String, manual: Boolean, source: String): Boolean {
        RuntimeState.lastOcrText = raw
        val screenState = ScreenStateDetector.detect(raw)
        runCatching { handleRideLifecycle(screenState) }

        val shouldTryOffer = manual || screenState == UberScreenState.OFFER || looksLikeOffer(raw)
        if (!shouldTryOffer) return screenState != UberScreenState.UNKNOWN

        val parsed = ScreenOfferParser.parse(raw)
        RuntimeState.captureConfidence = parsed.confidence
        val ride = parsed.ride
        if (ride == null) {
            if (manual) RuntimeState.captureStatus = "Não consegui ler a oferta pelo $source"
            RuntimeState.notifyChanged()
            return false
        }

        val signature = signature(ride)
        RuntimeState.ride = ride
        RuntimeState.manualConsumptionOverrideKml = null
        RuntimeState.recalculate()

        val analysis = RuntimeState.analysis ?: return false
        runCatching { RideHistoryStore.onOfferAnalyzed(this, ride, analysis) }
        RuntimeState.captureStatus = "Oferta lida"
        RuntimeState.captureReady = true
        RuntimeState.autoCaptureEnabled = true
        RuntimeState.notifyChanged()
        updatePill(analysis.grade)

        if (signature != lastSpokenSignature) {
            lastSpokenSignature = signature
            speakGrade(analysis.grade)
        }
        return true
    }

    private fun looksLikeOffer(raw: String): Boolean {
        val t = raw.lowercase(Locale.getDefault())
        val hasMoney = t.contains("r$") || Regex("\\b\\d+[,.]\\d{2}\\b").containsMatchIn(t)
        return hasMoney && t.contains("km") && (t.contains("aceitar") || t.contains("min") || t.contains("minuto"))
    }

    private fun handleRideLifecycle(state: UberScreenState) {
        when (state) {
            UberScreenState.TO_PICKUP, UberScreenState.ON_TRIP -> {
                if (RideHistoryStore.activeRecord(this) == null && RideHistoryStore.pendingOffer(this) != null) {
                    RideHistoryStore.confirmAccepted(this, "automatico")
                }
            }
            UberScreenState.COMPLETED -> {
                if (RideHistoryStore.activeRecord(this) != null) {
                    val avgConsumption = RuntimeState.obdConsumptionKml ?: RuntimeState.settings.baseConsumptionKml
                    RideHistoryStore.completeActive(
                        this,
                        actualDistanceKm = 0.0,
                        actualMinutes = 0.0,
                        averageConsumptionKml = avgConsumption,
                        settings = RuntimeState.settings
                    )
                }
            }
            else -> Unit
        }
    }

    private fun requestScreenshotOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || screenshotBusy) return
        val now = System.currentTimeMillis()
        if (now - lastScreenshotAt < SCREENSHOT_COOLDOWN_MS) {
            showNeutral("AGUARDE")
            return
        }
        lastScreenshotAt = now
        screenshotBusy = true

        // Evita que o próprio botão tampe dados importantes durante o OCR.
        overlayRoot?.visibility = View.INVISIBLE
        handler.postDelayed({ takeScreenshotSafely() }, 70L)
    }

    private fun takeScreenshotSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishScreenshot(false)
            return
        }
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val b = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        b
                    }.getOrNull()

                    if (bitmap == null) {
                        finishScreenshot(false)
                        return
                    }

                    val textRecognizer = recognizer ?: TextRecognition
                        .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .also { recognizer = it }

                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            val ok = runCatching {
                                processRawText(result.text, manual = true, source = "OCR")
                            }.getOrDefault(false)
                            if (!ok) {
                                showNeutral("NÃO LI")
                                speakSimple("Não consegui ler a oferta")
                            }
                        }
                        .addOnFailureListener {
                            showNeutral("NÃO LI")
                            speakSimple("Não consegui ler a oferta")
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            finishScreenshot(true)
                        }
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshot(false)
                }
            })
        }.onFailure {
            finishScreenshot(false)
        }
    }

    private fun finishScreenshot(processed: Boolean) {
        screenshotBusy = false
        if (!isBubbleHidden()) overlayRoot?.visibility = View.VISIBLE
        if (!processed) {
            showNeutral("NÃO LI")
            speakSimple("Não consegui ler a oferta")
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val values = LinkedHashSet<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (++visited > MAX_NODES) return
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(values::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(values::add)
            for (i in 0 until node.childCount) {
                if (visited > MAX_NODES) break
                walk(node.getChild(i))
            }
        }
        walk(root)
        return values.joinToString("\n")
    }

    private fun createOverlayIfNeeded() {
        if (overlayAdded) return
        val wm = windowManager ?: return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val pill = TextView(this).apply {
            text = "ANALISAR"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(Color.rgb(37, 78, 112))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_status_car, 0, 0, 0)
            compoundDrawablePadding = dp(7)
            contentDescription = "Analisar corrida atual"
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(230, 35, 35, 35))
            contentDescription = "Ocultar Corrida Ideal até abrir o app novamente"
            setOnClickListener { hideBubbleUntilAppReopen() }
        }

        root.addView(pill, LinearLayout.LayoutParams(dp(156), dp(58)))
        root.addView(close, LinearLayout.LayoutParams(dp(42), dp(58)).apply { marginStart = dp(4) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(150)
        }

        installDragAndTap(pill, root, lp)
        runCatching { wm.addView(root, lp) }
            .onSuccess { overlayAdded = true }
            .onFailure {
                RuntimeState.captureStatus = "Falha ao mostrar botão flutuante"
                RuntimeState.notifyChanged()
            }

        overlayRoot = root
        statusPill = pill
        overlayParams = lp
        if (isBubbleHidden()) root.visibility = View.GONE
    }

    private fun installDragAndTap(pill: TextView, root: View, lp: WindowManager.LayoutParams) {
        pill.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
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
                            lp.x = startX - dx.toInt()
                            lp.y = startY + dy.toInt()
                            runCatching { windowManager?.updateViewLayout(root, lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) analyzeCurrentScreen(manual = true)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showOverlayInternal() {
        createOverlayIfNeeded()
        if (!isBubbleHidden()) overlayRoot?.visibility = View.VISIBLE
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        runCatching { overlayRoot?.let { windowManager?.removeView(it) } }
        overlayAdded = false
        overlayRoot = null
        statusPill = null
        overlayParams = null
    }

    private fun updatePill(grade: RideGrade) {
        val (label, color) = when (grade) {
            RideGrade.GREEN -> "BOA" to Color.rgb(28, 154, 72)
            RideGrade.YELLOW -> "RAZOÁVEL" to Color.rgb(190, 142, 12)
            RideGrade.RED -> "RUIM" to Color.rgb(190, 46, 46)
        }
        statusPill?.text = label
        statusPill?.background = rounded(color)
    }

    private fun showNeutral(label: String) {
        statusPill?.text = label
        statusPill?.background = rounded(Color.rgb(70, 70, 70))
    }

    private fun speakGrade(grade: RideGrade) {
        when (grade) {
            RideGrade.GREEN -> speakSimple("Corrida boa")
            RideGrade.YELLOW -> speakSimple("Corrida razoável")
            RideGrade.RED -> speakSimple("Corrida ruim")
        }
    }

    private fun speakSimple(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "corrida-ideal-grade")
        } else {
            pendingSpeech = text
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("pt", "BR")
            pendingSpeech?.let {
                pendingSpeech = null
                speakSimple(it)
            }
        }
    }

    private fun loadSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val fuel = p.getFloat("fuel", 6.98f).toDouble()
        val base = p.getFloat("base", 12.6f).toDouble()
        RuntimeState.settings = AppSettings(
            fuelPrice = fuel,
            baseConsumptionKml = base,
            currentConsumptionKml = base,
            bestRealisticConsumptionKml = p.getFloat("best", 13.5f).toDouble(),
            targetGrossPerKm = p.getFloat("grosskm", 1.70f).toDouble(),
            targetNetPerHour = p.getFloat("neth", 35f).toDouble(),
            safetySpeedLimitKmh = p.getFloat("limit", 60f).toDouble(),
            tankCapacityLiters = p.getFloat("tank", 0f).toDouble()
        )
    }

    private fun isBubbleHidden(): Boolean = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        .getBoolean(KEY_HIDE_BUBBLE, false)

    private fun signature(r: RideInput) = "%.2f|%.2f|%.2f|%.0f".format(
        Locale.US, r.fare, r.pickupKm, r.rideKm, r.estimatedMinutes
    )

    private fun rounded(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(color)
        setStroke(dp(1), Color.argb(130, 255, 255, 255))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val UBER_PACKAGE = "com.ubercab.driver"
        private const val PREFS_UI = "accessibility_ui"
        private const val KEY_HIDE_BUBBLE = "bubble_hidden"
        private const val AUTO_DEBOUNCE_MS = 380L
        private const val AUTO_REPEAT_GUARD_MS = 1500L
        private const val SCREENSHOT_COOLDOWN_MS = 900L
        private const val MAX_NODES = 900

        @Volatile private var instance: UberAccessibilityService? = null

        fun showBubbleFromApp() {
            instance?.showBubbleFromApp()
        }

        fun analyzeNowFromApp() {
            instance?.analyzeNowFromApp()
        }

        fun isRunning(): Boolean = instance != null
    }
}
