package com.yuri.corridaideal

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var details: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var gradeText: TextView
    private lateinit var moneyText: TextView
    private lateinit var liveText: TextView
    private lateinit var adviceText: TextView
    private lateinit var speedText: TextView
    private lateinit var captureStatusText: TextView
    private lateinit var micButton: Button
    private lateinit var autoButton: Button

    private var locationManager: LocationManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var lastLocation: Location? = null
    private var tripDistanceMeters = 0.0
    private var tripStartElapsed = SystemClock.elapsedRealtime()
    private var blinking = false
    private var expanded = false
    private var lastActiveRecordId: String? = null
    private var consumptionSum = 0.0
    private var consumptionSamples = 0
    private val handler = Handler(Looper.getMainLooper())

    private val stateListener: (LiveSnapshot) -> Unit = { snap ->
        handler.post {
            if (snap.activeRideRecordId != null && snap.activeRideRecordId != lastActiveRecordId) {
                lastActiveRecordId = snap.activeRideRecordId
                resetTrip()
                consumptionSum = 0.0
                consumptionSamples = 0
            } else if (snap.activeRideRecordId == null) {
                lastActiveRecordId = null
            }
            render(snap)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createOverlay()
        setupLocation()
        setupSpeech()
        RuntimeState.addListener(stateListener)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LISTEN) listenNow()
        return START_NOT_STICKY
    }

    private fun promoteForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = 0
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (types != 0) startForeground(44, notification, types) else startForeground(44, notification)
        } else startForeground(44, notification)
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        bubble = TextView(this).apply {
            text = "📸"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(Color.rgb(70, 70, 70))
            setPadding(0, 0, 0, 0)
            contentDescription = "Ler oferta da tela"
        }
        root.addView(bubble, LinearLayout.LayoutParams(dp(62), dp(62)))

        details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.argb(238, 22, 22, 22))
            visibility = View.GONE
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "Corrida Ideal"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        top.addView(Button(this).apply {
            text = "×"
            textSize = 16f
            contentDescription = "Ocultar bolha até abrir o Corrida Ideal novamente"
            setOnClickListener { hideUntilAppReopen() }
        }, LinearLayout.LayoutParams(dp(44), dp(38)))

        gradeText = textView(18f, true)
        moneyText = textView(13f, false)
        speedText = textView(21f, true)
        liveText = textView(13f, false)
        captureStatusText = textView(12f, true)
        adviceText = textView(12f, false)

        val captureButton = Button(this).apply {
            text = "📸 LER OFERTA AGORA"
            setOnClickListener { captureOrRequestPermission() }
        }
        autoButton = Button(this).apply {
            text = "AUTO: DESLIGADO"
            setOnClickListener { toggleAuto() }
        }
        micButton = Button(this).apply {
            text = "🎙 Voz"
            setOnClickListener { listenNow() }
        }
        val resetButton = Button(this).apply {
            text = "↻ Zerar trecho"
            setOnClickListener { resetTrip() }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(micButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(resetButton, LinearLayout.LayoutParams(0, dp(44), 1f))

        val hideButton = Button(this).apply {
            text = "⏻ ENCERRAR / OCULTAR BOLHA"
            setOnClickListener { hideUntilAppReopen() }
        }

        val decisionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val acceptButton = Button(this).apply {
            text = "✓ ACEITEI"
            setOnClickListener { manualAccept() }
        }
        val rejectButton = Button(this).apply {
            text = "✕ NÃO ACEITEI"
            setOnClickListener { RideHistoryStore.rejectPending(this@OverlayService); speak("Oferta descartada.") }
        }
        decisionRow.addView(acceptButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        decisionRow.addView(rejectButton, LinearLayout.LayoutParams(0, dp(44), 1f))

        val finishRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val finishButton = Button(this).apply {
            text = "■ FINALIZAR"
            setOnClickListener { manualComplete() }
        }
        val reportButton = Button(this).apply {
            text = "📊 RELATÓRIO"
            setOnClickListener { openReport() }
        }
        finishRow.addView(finishButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        finishRow.addView(reportButton, LinearLayout.LayoutParams(0, dp(44), 1f))

        details.addView(top)
        details.addView(gradeText)
        details.addView(moneyText)
        details.addView(speedText)
        details.addView(liveText)
        details.addView(captureStatusText)
        details.addView(adviceText)
        details.addView(captureButton, full(top = 8))
        details.addView(autoButton, full(top = 4))
        details.addView(decisionRow, full(top = 4))
        details.addView(finishRow, full(top = 4))
        details.addView(row, full(top = 4))
        details.addView(hideButton, full(top = 6))
        root.addView(details)

        params = WindowManager.LayoutParams(
            dp(70), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(150)
        }

        installBubbleTouch()
        windowManager.addView(root, params)
        render(RuntimeState.snapshot())
    }

    private fun installBubbleTouch() {
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        downX = event.rawX; downY = event.rawY
                        downAt = System.currentTimeMillis(); moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        params.x = startX - dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(root, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val held = System.currentTimeMillis() - downAt
                            if (held >= 550) toggleExpanded() else captureOrRequestPermission()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun captureOrRequestPermission() {
        if (!RuntimeState.captureReady) {
            val i = Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }
        val i = Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE)
        startService(i)
        RuntimeState.captureStatus = "Lendo oferta…"
        RuntimeState.notifyChanged()
    }

    private fun toggleAuto() {
        if (!RuntimeState.captureReady) {
            captureOrRequestPermission()
            return
        }
        val turnOn = !RuntimeState.autoCaptureEnabled
        val action = if (turnOn) ScreenCaptureService.ACTION_AUTO_ON else ScreenCaptureService.ACTION_AUTO_OFF
        startService(Intent(this, ScreenCaptureService::class.java).setAction(action))
    }

    private fun manualAccept() {
        val r = RideHistoryStore.confirmAccepted(this, "botao")
        if (r != null) {
            resetTrip()
            consumptionSum = 0.0
            consumptionSamples = 0
            speak("Corrida aceita e adicionada ao relatório do dia.")
        } else speak("Leia uma oferta primeiro.")
    }

    private fun manualComplete() {
        val avg = if (consumptionSamples > 0) consumptionSum / consumptionSamples else RuntimeState.activeTripAvgConsumptionKml
        val done = RideHistoryStore.completeActive(
            this, RuntimeState.activeTripDistanceKm, RuntimeState.activeTripElapsedMinutes, avg, RuntimeState.settings
        )
        if (done != null) speak("Corrida finalizada. Resultado salvo no relatório do dia.")
        else speak("Não há corrida aceita em andamento.")
    }

    private fun openReport() {
        startActivity(Intent(this, ReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleExpanded() {
        expanded = !expanded
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        params.width = if (expanded) dp(310) else dp(70)
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun resetTrip() {
        tripDistanceMeters = 0.0
        tripStartElapsed = SystemClock.elapsedRealtime()
        lastLocation = null
        RuntimeState.tripAverageSpeedKmh = null
        RuntimeState.activeTripDistanceKm = 0.0
        RuntimeState.activeTripElapsedMinutes = 0.0
        RuntimeState.activeTripAvgConsumptionKml = null
        RuntimeState.notifyChanged()
    }

    private fun render(s: LiveSnapshot) {
        val a = s.analysis
        val grade = a?.grade
        val bubbleColor = when (grade) {
            RideGrade.GREEN -> Color.rgb(32, 145, 70)
            RideGrade.YELLOW -> Color.rgb(190, 145, 15)
            RideGrade.RED -> Color.rgb(185, 55, 55)
            null -> if (s.captureReady) Color.rgb(45, 105, 175) else Color.rgb(75, 75, 75)
        }
        bubble.background = circle(bubbleColor)
        bubble.text = when {
            s.activeRideRecordId != null -> "▶"
            s.autoCaptureEnabled -> "AUTO"
            else -> "📸"
        }
        bubble.textSize = if (s.autoCaptureEnabled && s.activeRideRecordId == null) 12f else 25f

        gradeText.text = when (grade) {
            RideGrade.GREEN -> "🟢 VERDE — VALE A META"
            RideGrade.YELLOW -> "🟡 AMARELA — DEPENDE"
            RideGrade.RED -> "🔴 VERMELHA — FORA DA META"
            null -> "⚪ Aguardando oferta"
        }
        gradeText.setTextColor(when (grade) {
            RideGrade.GREEN -> Color.rgb(90, 220, 120)
            RideGrade.YELLOW -> Color.rgb(255, 210, 70)
            RideGrade.RED -> Color.rgb(255, 95, 95)
            null -> Color.LTGRAY
        })

        moneyText.text = if (a != null) {
            "%.1f km • líquido R$ %.2f • R$ %.2f/km • R$ %.0f/h".format(a.totalKm, a.netValue, a.netPerKm, a.netPerHour)
        } else "Toque na bolha 📸 com a oferta da Uber visível"

        val speed = s.obdSpeedKmh ?: s.gpsSpeedKmh ?: 0.0
        val over = speed > s.settings.safetySpeedLimitKmh && s.settings.safetySpeedLimitKmh > 0
        speedText.text = if (over) "⚠ %.0f km/h".format(speed) else "%.0f km/h".format(speed)
        speedText.setTextColor(if (over) Color.rgb(255, 60, 60) else Color.WHITE)
        setBlinking(over)

        val usedConsumption = s.manualConsumptionOverrideKml ?: s.obdConsumptionKml ?: s.settings.currentConsumptionKml
        val req = a?.requiredConsumptionKml?.takeIf { it.isFinite() }
        val avgNeed = a?.requiredAverageSpeedKmh?.takeIf { it.isFinite() }
        val avgNow = s.tripAverageSpeedKmh
        liveText.text = buildString {
            append("Consumo: %.1f km/L".format(usedConsumption))
            if (req != null) append(" • necessário %.1f".format(req))
            if (avgNeed != null) append("\nMédia necessária: %.0f km/h".format(avgNeed))
            if (avgNow != null) append(" • atual %.0f".format(avgNow))
            append("\n${s.obdStatus}")
        }

        captureStatusText.text = s.captureStatus + if (s.captureConfidence > 0) " • leitura ${s.captureConfidence}%" else ""
        if (s.activeRideRecordId != null) captureStatusText.append("\n▶ ${s.trackingStatus}")
        autoButton.text = if (s.autoCaptureEnabled) "AUTO: LIGADO" else "AUTO: DESLIGADO"

        if (a != null) {
            val learned = req?.let { EfficiencyProfile(this).rangesMeeting(it) }
            adviceText.text = when {
                learned != null -> "Faixa observada no seu carro para esse consumo: $learned"
                req != null -> "Para ficar verde: ≥ %.1f km/L e média ≈ %.0f km/h. A média não substitui o limite da via.".format(req, avgNeed ?: 0.0)
                else -> a.reason
            }
        } else {
            adviceText.text = "Uso normal: 1 toque na bolha = ler a oferta. Segure a bolha = abrir detalhes. Arraste para mudar de lugar."
        }
    }

    private fun textView(size: Float, bold: Boolean) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(150, 255, 255, 255))
    }

    private fun full(top: Int = 2) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun setBlinking(active: Boolean) {
        if (active == blinking) return
        blinking = active
        handler.removeCallbacks(blinkRunnable)
        speedText.alpha = 1f
        if (active) handler.post(blinkRunnable)
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!blinking) { speedText.alpha = 1f; return }
            speedText.alpha = if (speedText.alpha > 0.7f) 0.35f else 1f
            handler.postDelayed(this, 450)
        }
    }

    private fun setupLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) RuntimeState.gpsSpeedKmh = location.speed * 3.6
        val last = lastLocation
        if (last != null && location.accuracy <= 40f) {
            val d = last.distanceTo(location).toDouble()
            if (d in 0.2..150.0) tripDistanceMeters += d
        }
        lastLocation = location
        val elapsedMs = SystemClock.elapsedRealtime() - tripStartElapsed
        val hours = elapsedMs / 3_600_000.0
        if (hours > 0.001) RuntimeState.tripAverageSpeedKmh = (tripDistanceMeters / 1000.0) / hours
        if (RuntimeState.activeRideRecordId != null) {
            val c = RuntimeState.manualConsumptionOverrideKml ?: RuntimeState.obdConsumptionKml
            if (c != null && c in 2.0..60.0) {
                consumptionSum += c
                consumptionSamples++
                RuntimeState.activeTripAvgConsumptionKml = consumptionSum / consumptionSamples
            }
            RuntimeState.activeTripDistanceKm = tripDistanceMeters / 1000.0
            RuntimeState.activeTripElapsedMinutes = elapsedMs / 60_000.0
        }
        RuntimeState.notifyChanged()
    }

    private fun setupSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { micButton.text = "🎙 Ouvindo…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { micButton.text = "🎙 Voz" }
                override fun onError(error: Int) { micButton.text = "🎙 Voz"; speak("Não consegui entender.") }
                override fun onResults(results: Bundle?) {
                    micButton.text = "🎙 Voz"
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    applyVoiceCommand(VoiceParser.parse(heard))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun listenNow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Abra o aplicativo e permita o microfone."); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex.: consumo 13 vírgula 5")
        }
        runCatching { speechRecognizer?.startListening(intent) }
    }

    private fun applyVoiceCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Ride -> {
                RuntimeState.ride = RideInput(command.fare, command.pickupKm, command.rideKm, command.minutes)
                RuntimeState.manualConsumptionOverrideKml = null
                resetTrip(); RuntimeState.recalculate(); speak(statusSentence())
            }
            is VoiceCommand.CurrentConsumption -> {
                RuntimeState.manualConsumptionOverrideKml = command.kml
                RuntimeState.recalculate(); speak("Consumo atualizado para %.1f quilômetros por litro.".format(command.kml))
            }
            is VoiceCommand.SaveBaseConsumption -> {
                RuntimeState.settings = RuntimeState.settings.copy(baseConsumptionKml = command.kml, currentConsumptionKml = command.kml)
                RuntimeState.manualConsumptionOverrideKml = null
                RuntimeState.recalculate(); speak("Média base salva.")
            }
            is VoiceCommand.SpeedLimit -> {
                RuntimeState.settings = RuntimeState.settings.copy(safetySpeedLimitKmh = command.kmh)
                RuntimeState.recalculate(); speak("Alerta configurado em %.0f quilômetros por hora.".format(command.kmh))
            }
            VoiceCommand.Status -> speak(statusSentence())
            VoiceCommand.Unknown -> speak("Use a câmera para ler a oferta. Por voz, você pode dizer consumo 13 vírgula 5, limite 60, ou como está a corrida.")
        }
    }

    private fun statusSentence(): String {
        val a = RuntimeState.analysis ?: return "Ainda não tenho uma corrida para analisar."
        val grade = when (a.grade) { RideGrade.GREEN -> "verde"; RideGrade.YELLOW -> "amarela"; RideGrade.RED -> "vermelha" }
        return "Corrida $grade. %.2f reais líquidos por quilômetro e %.0f reais líquidos por hora.".format(a.netPerKm, a.netPerHour)
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "corrida-status")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
            ttsReady = true
            pendingSpeech?.let { queued ->
                pendingSpeech = null
                tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "corrida-status")
            }
        }
    }

    private fun hideUntilAppReopen() {
        getSharedPreferences("overlay_control", MODE_PRIVATE)
            .edit()
            .putBoolean("reopen_on_next_app_open", true)
            .apply()

        // Encerra também a leitura contínua de tela para não gastar bateria
        // depois que o motorista terminou o expediente. Ao reabrir o app, a
        // bolha volta; a autorização de captura pode ser ativada novamente.
        stopService(Intent(this, ScreenCaptureService::class.java))
        RuntimeState.autoCaptureEnabled = false
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura de tela desligada"
        RuntimeState.notifyChanged()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Corrida Ideal ativo", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val listen = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corrida Ideal ativo")
            .setContentText("Bolha 📸 pronta para analisar ofertas")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_btn_speak_now, "Voz", listen)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        locationManager?.removeUpdates(this)
        speechRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
        runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val CHANNEL_ID = "corrida_ideal_live"
        const val ACTION_LISTEN = "com.yuri.corridaideal.LISTEN"
    }
}
