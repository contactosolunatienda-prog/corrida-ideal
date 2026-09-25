package com.yuri.corridaideal

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var fare: EditText
    private lateinit var pickup: EditText
    private lateinit var ride: EditText
    private lateinit var minutes: EditText
    private lateinit var fuel: EditText
    private lateinit var baseConsumption: EditText
    private lateinit var bestConsumption: EditText
    private lateinit var grossPerKmTarget: EditText
    private lateinit var netPerHourTarget: EditText
    private lateinit var speedLimit: EditText
    private lateinit var tankCapacity: EditText
    private lateinit var trackingStatus: TextView
    private lateinit var result: TextView
    private lateinit var obdStatus: TextView
    private lateinit var obdSpinner: Spinner
    private lateinit var screenStatus: TextView
    private var waitingOverlayPermission = false
    private val obdAddresses = mutableListOf<String>()

    private val stateListener: (LiveSnapshot) -> Unit = { snap ->
        runOnUiThread {
            obdStatus.text = buildString {
                append(snap.obdStatus)
                snap.obdConsumptionKml?.let { append(" • %.1f km/L".format(it)) }
                snap.obdSpeedKmh?.let { append(" • %.0f km/h".format(it)) }
            }
            snap.analysis?.let { result.text = formatAnalysis(it) }
            if (::screenStatus.isInitialized) {
                screenStatus.text = snap.captureStatus + if (snap.captureConfidence > 0) " • leitura ${snap.captureConfidence}%" else ""
            }
            if (::trackingStatus.isInitialized) trackingStatus.text = snap.trackingStatus
            // When OCR reads an Uber offer, mirror the exact values into the visible
            // fields so the numbers on screen always match the analysis being shown.
            if (snap.captureConfidence > 0) {
                snap.ride?.let { r ->
                    if (!fare.hasFocus()) fare.setText(decimal(r.fare))
                    if (!pickup.hasFocus()) pickup.setText(decimal(r.pickupKm))
                    if (!ride.hasFocus()) ride.setText(decimal(r.rideKm))
                    if (!minutes.hasFocus()) minutes.setText(decimal(r.estimatedMinutes))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale("pt", "BR"))
        setContentView(buildUi())
        loadPrefs()
        RideHistoryStore.activeRecord(this)?.let {
            RuntimeState.activeRideRecordId = it.id
            RuntimeState.trackingStatus = "Corrida em andamento recuperada"
        }
        requestRuntimePermissions()
        RuntimeState.addListener(stateListener)
        analyzeRide(silent = true)
    }

    override fun onResume() {
        super.onResume()
        populateBondedDevices()
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false
            startOverlay()
            return
        }

        // Se o motorista ocultou a bolha pelo X/Encerrar, ela volta automaticamente
        // somente quando o Corrida Ideal for aberto novamente.
        val control = getSharedPreferences("overlay_control", MODE_PRIVATE)
        if (control.getBoolean("reopen_on_next_app_open", false) && Settings.canDrawOverlays(this)) {
            control.edit().putBoolean("reopen_on_next_app_open", false).apply()
            startOverlay()
        }
    }

    override fun onDestroy() {
        RuntimeState.removeListener(stateListener)
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Corrida Ideal"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Analisa a oferta, calcula o consumo mínimo e a média necessária, e acompanha OBD2/GPS em um painel flutuante."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(section("OFERTA"))
        fare = field(root, "Valor da corrida (R$)", "27,50")
        pickup = field(root, "Km até o passageiro", "3")
        ride = field(root, "Km da viagem", "12")
        minutes = field(root, "Tempo total estimado (min)", "28")

        val analyze = Button(this).apply {
            text = "ANALISAR CORRIDA"
            setOnClickListener { analyzeRide() }
        }
        root.addView(analyze, full())

        result = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 35, 35))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(result, full(top = 10))

        root.addView(section("SEUS PARÂMETROS"))
        fuel = field(root, "Gasolina (R$/L)", "6,98")
        baseConsumption = field(root, "Consumo base do carro (km/L)", "12,6")
        bestConsumption = field(root, "Melhor consumo realista (km/L)", "13,5")
        grossPerKmTarget = field(root, "Meta VERDE bruta mínima (R$/km total)", "1,70")
        root.addView(TextView(this).apply {
            text = "Faixa amarela começa automaticamente em 80% da meta verde (com R$ 1,70, cerca de R$ 1,36/km)."
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
        netPerHourTarget = field(root, "Meta VERDE líquida mínima (R$/hora)", "35")
        speedLimit = field(root, "Teto de alerta de velocidade (km/h)", "60")
        tankCapacity = field(root, "Capacidade do tanque (L) — opcional", "0")
        root.addView(TextView(this).apply {
            text = "Se informar a capacidade do tanque e o OBD disponibilizar nível de combustível, o relatório estima litros restantes e autonomia."
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })

        val save = Button(this).apply {
            text = "SALVAR PARÂMETROS"
            setOnClickListener { saveSettingsAndRecalculate() }
        }
        root.addView(save, full())

        root.addView(section("BOLHA FLUTUANTE + LEITURA DA UBER"))
        root.addView(TextView(this).apply {
            text = "Uso principal: ative a bolha, abra a Uber e, quando chegar a oferta, toque uma vez em 📸. Na primeira vez o Android pedirá autorização para compartilhar a tela/app. Depois a leitura usa OCR no próprio celular e calcula a corrida automaticamente."
            textSize = 13f
            setPadding(0,0,0,dp(8))
        })

        val overlayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startOverlay = Button(this).apply {
            text = "ATIVAR BOLHA 📸"
            setOnClickListener { startOverlay() }
        }
        val screen = Button(this).apply {
            text = "ATIVAR LEITURA"
            setOnClickListener { startActivity(Intent(this@MainActivity, CapturePermissionActivity::class.java)) }
        }
        overlayRow.addView(startOverlay, LinearLayout.LayoutParams(0, dp(52), 1f))
        overlayRow.addView(screen, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(overlayRow)

        screenStatus = TextView(this).apply {
            text = "Leitura de tela desligada"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(screenStatus)

        root.addView(TextView(this).apply {
            text = "Bolha: toque = ler oferta • segure = abrir detalhes • arraste = mover. O modo AUTO fica dentro dos detalhes e é experimental. Voz fica como alternativa para consumo, limite e status."
            textSize = 12f
        })

        trackingStatus = TextView(this).apply {
            text = RuntimeState.trackingStatus
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(trackingStatus)

        val reportButton = Button(this).apply {
            text = "📊 RELATÓRIO DO DIA / ÚLTIMAS CORRIDAS"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReportActivity::class.java)) }
        }
        root.addView(reportButton, full(top = 6))

        val clearVoice = Button(this).apply {
            text = "Usar OBD/base novamente (tirar consumo falado)"
            setOnClickListener {
                RuntimeState.manualConsumptionOverrideKml = null
                RuntimeState.recalculate()
                Toast.makeText(this@MainActivity, "Consumo por voz removido", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearVoice, full(top = 6))

        root.addView(section("OBD2 BLUETOOTH"))
        root.addView(TextView(this).apply {
            text = "Primeiro pareie o seu ELM327/OBD2 nas configurações Bluetooth do Android. Depois selecione abaixo."
            textSize = 13f
        })
        obdSpinner = Spinner(this)
        root.addView(obdSpinner, full(top = 8))

        val obdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connect = Button(this).apply {
            text = "CONECTAR OBD"
            setOnClickListener { connectSelectedObd() }
        }
        val disconnect = Button(this).apply {
            text = "DESCONECTAR"
            setOnClickListener { ObdHub.disconnect() }
        }
        obdRow.addView(connect, LinearLayout.LayoutParams(0, dp(50), 1f))
        obdRow.addView(disconnect, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(obdRow)

        obdStatus = TextView(this).apply {
            text = "OBD desconectado"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(obdStatus)

        val learned = TextView(this).apply {
            val best = EfficiencyProfile(this@MainActivity).bestObservedRange()
            text = if (best != null) "Faixa mais eficiente já observada: $best"
            else "Aprendizado OBD: ainda faltam amostras para estimar a faixa mais eficiente do seu carro."
            textSize = 13f
        }
        root.addView(learned)

        val resetLearning = Button(this).apply {
            text = "Limpar aprendizado de velocidade × consumo"
            setOnClickListener {
                EfficiencyProfile(this@MainActivity).clear()
                Toast.makeText(this@MainActivity, "Aprendizado zerado", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(resetLearning, full(top = 6))

        root.addView(section("COMO A DECISÃO É FEITA"))
        root.addView(TextView(this).apply {
            text = "🟢 Verde: bate a meta BRUTA por km total e a meta LÍQUIDA por hora.\n\n" +
                "🟡 Amarela: fica perto da meta ou pode bater com consumo/trânsito melhores, dentro do cenário realista.\n\n" +
                "🔴 Vermelha: retorno por km/hora abaixo do aceitável ou cenário necessário fora do realista.\n\n" +
                "A velocidade mostrada é uma média econômica/temporal. Nunca substitui o limite legal da via."
            textSize = 14f
        })
        return scroll
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun field(parent: LinearLayout, label: String, initial: String): EditText {
        val e = EditText(this).apply {
            hint = label
            setText(initial)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelectAllOnFocus(true)
        }
        parent.addView(TextView(this).apply { text = label; textSize = 13f })
        parent.addView(e, full())
        return e
    }

    private fun analyzeRide(silent: Boolean = false) {
        val input = RideInput(
            num(fare, 27.5), num(pickup, 3.0), num(ride, 12.0), num(minutes, 28.0)
        )
        RuntimeState.ride = input
        RuntimeState.settings = currentSettings()
        RuntimeState.recalculate()
        result.text = RuntimeState.analysis?.let(::formatAnalysis) ?: "Sem análise"
        if (!silent) savePrefs()
    }

    private fun saveSettingsAndRecalculate() {
        RuntimeState.settings = currentSettings()
        savePrefs()
        RuntimeState.recalculate()
        Toast.makeText(this, "Parâmetros salvos", Toast.LENGTH_SHORT).show()
    }

    private fun currentSettings() = AppSettings(
        fuelPrice = num(fuel, 6.98),
        baseConsumptionKml = num(baseConsumption, 12.6),
        currentConsumptionKml = num(baseConsumption, 12.6),
        bestRealisticConsumptionKml = num(bestConsumption, 13.5),
        targetGrossPerKm = num(grossPerKmTarget, 1.70),
        targetNetPerHour = num(netPerHourTarget, 35.0),
        safetySpeedLimitKmh = num(speedLimit, 60.0),
        tankCapacityLiters = num(tankCapacity, 0.0)
    )

    private fun formatAnalysis(a: RideAnalysis): String {
        val icon = when (a.grade) {
            RideGrade.GREEN -> "🟢 VERDE"
            RideGrade.YELLOW -> "🟡 AMARELA"
            RideGrade.RED -> "🔴 VERMELHA"
        }
        return buildString {
            append("$icon\n")
            append("Distância total: %.1f km\n".format(a.totalKm))
            append("Combustível estimado: R$ %.2f\n".format(a.fuelCost))
            append("Líquido combustível: R$ %.2f\n".format(a.netValue))
            append("Bruto: R$ %.2f/km • Líquido: R$ %.2f/km\n".format(a.grossPerKm, a.netPerKm))
            append("Líquido por hora: R$ %.2f/h\n".format(a.netPerHour))
            a.requiredConsumptionKml?.takeIf { it.isFinite() }?.let {
                append("Consumo necessário no tempo atual: %.1f km/L\n".format(it))
            }
            a.requiredAverageSpeedKmh?.takeIf { it.isFinite() }?.let {
                append("Média necessária com consumo-base: ≈ %.0f km/h\n".format(it))
            }
            a.maxMinutesForTarget?.takeIf { it.isFinite() }?.let {
                append("Tempo máximo para bater R$/h: ≈ %.0f min\n".format(it))
            }
            append("\n${a.reason}")
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            waitingOverlayPermission = true
            startActivity(intent)
            Toast.makeText(this, "Ative 'Exibir sobre outros apps'. Ao voltar, a bolha será iniciada.", Toast.LENGTH_LONG).show()
            return
        }
        analyzeRide(silent = true)
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Painel flutuante ativado", Toast.LENGTH_SHORT).show()
    }

    private fun sendVoiceAction() {
        if (!Settings.canDrawOverlays(this)) {
            startOverlay(); return
        }
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_LISTEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun connectSelectedObd() {
        if (obdAddresses.isEmpty()) {
            Toast.makeText(this, "Nenhum OBD pareado encontrado", Toast.LENGTH_LONG).show()
            return
        }
        val pos = obdSpinner.selectedItemPosition.coerceIn(0, obdAddresses.lastIndex)
        ObdHub.connect(this, obdAddresses[pos])
    }

    private fun populateBondedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val devices = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
        obdAddresses.clear()
        val labels = devices.sortedBy { it.name ?: it.address }.map {
            obdAddresses.add(it.address)
            "${it.name ?: "Bluetooth"} • ${it.address}"
        }
        val shown = if (labels.isEmpty()) listOf("Nenhum dispositivo pareado") else labels
        obdSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shown)
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 500)
    }

    private fun savePrefs() {
        val p = getSharedPreferences("settings", MODE_PRIVATE).edit()
        p.putFloat("fuel", num(fuel, 6.98).toFloat())
        p.putFloat("base", num(baseConsumption, 12.6).toFloat())
        p.putFloat("best", num(bestConsumption, 13.5).toFloat())
        p.putFloat("grosskm", num(grossPerKmTarget, 1.70).toFloat())
        p.putFloat("neth", num(netPerHourTarget, 35.0).toFloat())
        p.putFloat("limit", num(speedLimit, 60.0).toFloat())
        p.putFloat("tank", num(tankCapacity, 0.0).toFloat())
        p.apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        fuel.setText(decimal(p.getFloat("fuel", 6.98f).toDouble()))
        baseConsumption.setText(decimal(p.getFloat("base", 12.6f).toDouble()))
        bestConsumption.setText(decimal(p.getFloat("best", 13.5f).toDouble()))
        grossPerKmTarget.setText(decimal(p.getFloat("grosskm", 1.70f).toDouble()))
        netPerHourTarget.setText(decimal(p.getFloat("neth", 35f).toDouble()))
        speedLimit.setText(decimal(p.getFloat("limit", 60f).toDouble()))
        tankCapacity.setText(decimal(p.getFloat("tank", 0f).toDouble()))
    }

    private fun decimal(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v).trimEnd('0')

    private fun num(e: EditText, fallback: Double): Double = e.text.toString()
        .trim().replace(',', '.').toDoubleOrNull() ?: fallback

    private fun full(top: Int = 2) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
