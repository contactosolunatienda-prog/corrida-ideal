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
    private lateinit var netPerKmTarget: EditText
    private lateinit var netPerHourTarget: EditText
    private lateinit var speedLimit: EditText
    private lateinit var result: TextView
    private lateinit var obdStatus: TextView
    private lateinit var obdSpinner: Spinner
    private val obdAddresses = mutableListOf<String>()

    private val stateListener: (LiveSnapshot) -> Unit = { snap ->
        runOnUiThread {
            obdStatus.text = buildString {
                append(snap.obdStatus)
                snap.obdConsumptionKml?.let { append(" • %.1f km/L".format(it)) }
                snap.obdSpeedKmh?.let { append(" • %.0f km/h".format(it)) }
            }
            snap.analysis?.let { result.text = formatAnalysis(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale("pt", "BR"))
        setContentView(buildUi())
        loadPrefs()
        requestRuntimePermissions()
        RuntimeState.addListener(stateListener)
        analyzeRide(silent = true)
    }

    override fun onResume() {
        super.onResume()
        populateBondedDevices()
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
        bestConsumption = field(root, "Melhor consumo realista (km/L)", "14,0")
        netPerKmTarget = field(root, "Meta líquida mínima (R$/km)", "1,20")
        netPerHourTarget = field(root, "Meta líquida mínima (R$/hora)", "35")
        speedLimit = field(root, "Teto de alerta de velocidade (km/h)", "60")

        val save = Button(this).apply {
            text = "SALVAR PARÂMETROS"
            setOnClickListener { saveSettingsAndRecalculate() }
        }
        root.addView(save, full())

        root.addView(section("PAINEL FLUTUANTE + VOZ"))
        root.addView(TextView(this).apply {
            text = "Exemplos: “corrida 27 vírgula 50, 3 até buscar, 12 de viagem, 28 minutos”; “consumo 13 vírgula 5”; “como está a corrida?”"
            textSize = 13f
            setPadding(0,0,0,dp(8))
        })

        val overlayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startOverlay = Button(this).apply {
            text = "ATIVAR PAINEL"
            setOnClickListener { startOverlay() }
        }
        val voice = Button(this).apply {
            text = "🎙 FALAR"
            setOnClickListener { sendVoiceAction() }
        }
        overlayRow.addView(startOverlay, LinearLayout.LayoutParams(0, dp(52), 1f))
        overlayRow.addView(voice, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(overlayRow)

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
            text = "🟢 Verde: já bate suas metas líquidas por km e por hora.\n\n" +
                "🟡 Amarela: ainda não bate, mas pode bater dentro do melhor consumo realista e do teto de velocidade configurado. O app mostra exatamente o km/L e a média necessários.\n\n" +
                "🔴 Vermelha: para bater a meta exigiria consumo ou média de deslocamento fora do cenário que você definiu como realista.\n\n" +
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
        bestRealisticConsumptionKml = num(bestConsumption, 14.0),
        targetNetPerKm = num(netPerKmTarget, 1.20),
        targetNetPerHour = num(netPerHourTarget, 35.0),
        safetySpeedLimitKmh = num(speedLimit, 60.0)
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
                append("Consumo-alvo do par: %.1f km/L\n".format(it))
            }
            a.requiredAverageSpeedKmh?.takeIf { it.isFinite() }?.let {
                append("Média do par para virar verde: ≈ %.0f km/h\n".format(it))
            }
            a.minimumConsumptionForKmTargetKml?.takeIf { it.isFinite() }?.let {
                append("Mínimo absoluto só para bater R$/km: %.1f km/L\n".format(it))
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
            startActivity(intent)
            Toast.makeText(this, "Ative 'Exibir sobre outros apps' e volte", Toast.LENGTH_LONG).show()
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
        p.putFloat("best", num(bestConsumption, 14.0).toFloat())
        p.putFloat("netkm", num(netPerKmTarget, 1.20).toFloat())
        p.putFloat("neth", num(netPerHourTarget, 35.0).toFloat())
        p.putFloat("limit", num(speedLimit, 60.0).toFloat())
        p.apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        fuel.setText(decimal(p.getFloat("fuel", 6.98f).toDouble()))
        baseConsumption.setText(decimal(p.getFloat("base", 12.6f).toDouble()))
        bestConsumption.setText(decimal(p.getFloat("best", 14f).toDouble()))
        netPerKmTarget.setText(decimal(p.getFloat("netkm", 1.20f).toDouble()))
        netPerHourTarget.setText(decimal(p.getFloat("neth", 35f).toDouble()))
        speedLimit.setText(decimal(p.getFloat("limit", 60f).toDouble()))
    }

    private fun decimal(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v).trimEnd('0')

    private fun num(e: EditText, fallback: Double): Double = e.text.toString()
        .trim().replace(',', '.').toDoubleOrNull() ?: fallback

    private fun full(top: Int = 2) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
