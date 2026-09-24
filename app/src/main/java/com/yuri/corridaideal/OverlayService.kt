package com.yuri.corridaideal

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import kotlin.math.roundToInt

class OverlayService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var locationManager: LocationManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var lastLocation: Location? = null
    private var tripDistanceMeters = 0.0
    private var tripStartElapsed = SystemClock.elapsedRealtime()
    private var blinking = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var gradeText: TextView
    private lateinit var moneyText: TextView
    private lateinit var liveText: TextView
    private lateinit var adviceText: TextView
    private lateinit var speedText: TextView
    private lateinit var micButton: Button

    private val stateListener: (LiveSnapshot) -> Unit = { snap ->
        handler.post { render(snap) }
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
        } else {
            startForeground(44, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LISTEN) listenNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.argb(220, 22, 22, 22))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Corrida Ideal"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val spacer = Space(this)
        val close = Button(this).apply {
            text = "×"
            textSize = 16f
            setPadding(0,0,0,0)
            setOnClickListener { stopSelf() }
        }
        top.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f))
        top.addView(spacer, LinearLayout.LayoutParams(dp(4), 1))
        top.addView(close, LinearLayout.LayoutParams(dp(40), dp(34)))

        gradeText = textView(18f, true)
        moneyText = textView(13f, false)
        liveText = textView(13f, false)
        adviceText = textView(12f, false)
        speedText = textView(22f, true)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        micButton = Button(this).apply {
            text = "🎙 Falar"
            setOnClickListener { listenNow() }
        }
        val resetButton = Button(this).apply {
            text = "↻"
            setOnClickListener {
                tripDistanceMeters = 0.0
                tripStartElapsed = SystemClock.elapsedRealtime()
                lastLocation = null
                RuntimeState.tripAverageSpeedKmh = null
                RuntimeState.notifyChanged()
            }
        }
        bottom.addView(micButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(resetButton, LinearLayout.LayoutParams(dp(52), dp(44)))

        root.addView(top)
        root.addView(gradeText)
        root.addView(moneyText)
        root.addView(speedText)
        root.addView(liveText)
        root.addView(adviceText)
        root.addView(bottom)

        params = WindowManager.LayoutParams(
            dp(285), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(120)
        }

        root.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(root, params) }
                        return true
                    }
                }
                return false
            }
        })

        overlayView = root
        windowManager.addView(root, params)
        render(RuntimeState.snapshot())
    }

    private fun textView(size: Float, bold: Boolean) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun render(s: LiveSnapshot) {
        val a = s.analysis
        val grade = a?.grade
        gradeText.text = when (grade) {
            RideGrade.GREEN -> "🟢 VERDE — VALE A META"
            RideGrade.YELLOW -> "🟡 AMARELA — DEPENDE"
            RideGrade.RED -> "🔴 VERMELHA — FORA DA META"
            null -> "⚪ Aguardando corrida"
        }
        gradeText.setTextColor(when (grade) {
            RideGrade.GREEN -> Color.rgb(90, 220, 120)
            RideGrade.YELLOW -> Color.rgb(255, 210, 70)
            RideGrade.RED -> Color.rgb(255, 95, 95)
            null -> Color.LTGRAY
        })

        if (a != null) {
            moneyText.text = "%.1f km • líquido R$ %.2f • R$ %.2f/km • R$ %.0f/h".format(
                a.totalKm, a.netValue, a.netPerKm, a.netPerHour
            )
        } else moneyText.text = "Fale ou digite uma oferta"

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
            if (req != null) append(" • alvo %.1f".format(req))
            if (avgNeed != null) append("\nMédia-alvo: %.0f km/h".format(avgNeed))
            if (avgNow != null) append(" • atual %.0f".format(avgNow))
            append("\n${s.obdStatus}")
            if (s.manualConsumptionOverrideKml != null) append(" • consumo por voz")
        }

        if (a != null) {
            val profile = EfficiencyProfile(this)
            val learned = req?.let { profile.rangesMeeting(it) }
            adviceText.text = when {
                learned != null -> "Faixa observada no seu carro para esse consumo: $learned"
                req != null -> "OBD ainda aprendendo a faixa de velocidade que entrega ≥ %.1f km/L.".format(req)
                else -> a.reason
            }
        } else adviceText.text = "Toque em 🎙 e diga: corrida 27,50; 3 até buscar; 12 de viagem; 28 minutos."
    }

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
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) RuntimeState.gpsSpeedKmh = location.speed * 3.6
        val last = lastLocation
        if (last != null && location.accuracy <= 40f) {
            val d = last.distanceTo(location).toDouble()
            if (d in 0.2..150.0) tripDistanceMeters += d
        }
        lastLocation = location
        val hours = (SystemClock.elapsedRealtime() - tripStartElapsed) / 3_600_000.0
        if (hours > 0.001) RuntimeState.tripAverageSpeedKmh = (tripDistanceMeters / 1000.0) / hours
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
                override fun onEndOfSpeech() { micButton.text = "🎙 Falar" }
                override fun onError(error: Int) {
                    micButton.text = "🎙 Falar"
                    speak("Não consegui entender. Tente novamente.")
                }
                override fun onResults(results: Bundle?) {
                    micButton.text = "🎙 Falar"
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = list?.firstOrNull() ?: return
                    applyVoiceCommand(VoiceParser.parse(heard))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun listenNow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Abra o aplicativo e permita o microfone.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga os dados da corrida")
        }
        runCatching { speechRecognizer?.startListening(intent) }
    }

    private fun applyVoiceCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Ride -> {
                RuntimeState.ride = RideInput(command.fare, command.pickupKm, command.rideKm, command.minutes)
                RuntimeState.manualConsumptionOverrideKml = null
                tripDistanceMeters = 0.0
                tripStartElapsed = SystemClock.elapsedRealtime()
                lastLocation = null
                RuntimeState.recalculate()
                speak(statusSentence())
            }
            is VoiceCommand.CurrentConsumption -> {
                RuntimeState.manualConsumptionOverrideKml = command.kml
                RuntimeState.recalculate()
                speak("Consumo da corrida atualizado para %.1f quilômetros por litro.".format(command.kml))
            }
            is VoiceCommand.SaveBaseConsumption -> {
                RuntimeState.settings = RuntimeState.settings.copy(
                    baseConsumptionKml = command.kml,
                    currentConsumptionKml = command.kml
                )
                RuntimeState.manualConsumptionOverrideKml = null
                RuntimeState.recalculate()
                speak("Média base salva em %.1f quilômetros por litro.".format(command.kml))
            }
            is VoiceCommand.SpeedLimit -> {
                RuntimeState.settings = RuntimeState.settings.copy(safetySpeedLimitKmh = command.kmh)
                RuntimeState.recalculate()
                speak("Limite de alerta configurado em %.0f quilômetros por hora.".format(command.kmh))
            }
            VoiceCommand.Status -> speak(statusSentence())
            VoiceCommand.Unknown -> speak("Comando não reconhecido. Diga corrida, consumo, limite ou como está a corrida.")
        }
    }

    private fun statusSentence(): String {
        val a = RuntimeState.analysis ?: return "Ainda não tenho uma corrida para analisar."
        val grade = when (a.grade) {
            RideGrade.GREEN -> "verde"
            RideGrade.YELLOW -> "amarela"
            RideGrade.RED -> "vermelha"
        }
        val req = a.requiredConsumptionKml?.takeIf { it.isFinite() }
        val speed = a.requiredAverageSpeedKmh?.takeIf { it.isFinite() }
        return buildString {
            append("Corrida $grade. Líquido estimado %.2f reais. ".format(a.netValue))
            append("%.2f reais líquidos por quilômetro e %.0f reais por hora. ".format(a.netPerKm, a.netPerHour))
            if (req != null) append("Para virar verde, consumo alvo %.1f por litro. ".format(req))
            if (speed != null) append("Média de deslocamento necessária perto de %.0f quilômetros por hora.".format(speed))
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "corrida-status")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("pt", "BR")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Corrida Ideal ativo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val listen = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corrida Ideal ativo")
            .setContentText("Análise, velocidade e OBD em primeiro plano")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_btn_speak_now, "Falar", listen)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        locationManager?.removeUpdates(this)
        speechRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val CHANNEL_ID = "corrida_ideal_live"
        const val ACTION_LISTEN = "com.yuri.corridaideal.LISTEN"
    }
}
