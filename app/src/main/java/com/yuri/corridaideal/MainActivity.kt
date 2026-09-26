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
    private lateinit var tankCapacity: EditText
    private lateinit var result: TextView
    private lateinit var obdStatus: TextView
    private lateinit var obdSpinner: Spinner
    private lateinit var captureStatus: TextView
    private lateinit var panelStatus: TextView
    private val obdAddresses = mutableListOf<String>()
    private var connectObdAfterPermission = false
    private var waitingOverlayPermission = false

    private val stateListener: (LiveSnapshot) -> Unit = { snap ->
        runOnUiThread {
            if (::obdStatus.isInitialized) {
                obdStatus.text = buildString {
                    append(snap.obdStatus)
                    snap.obdConsumptionKml?.let { append(" • %.1f km/L".format(it)) }
                    snap.obdSpeedKmh?.let { append(" • %.0f km/h".format(it)) }
                }
            }
            snap.analysis?.let { if (::result.isInitialized) result.text = formatAnalysis(it) }
            if (::captureStatus.isInitialized) {
                captureStatus.text = when {
                    snap.captureReady -> "✓ LEITURA ATIVA — vá para a Uber e use o painel."
                    snap.captureStatus == "TENTAR" -> "Leitura precisa ser ativada novamente pelo painel."
                    else -> "Leitura desligada — ative pelo painel já dentro da Uber."
                }
            }
            if (::panelStatus.isInitialized) {
                panelStatus.text = if (Settings.canDrawOverlays(this@MainActivity)) {
                    "✓ Permissão da janela concedida"
                } else {
                    "Permissão da janela ainda não concedida"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale("pt", "BR"))
        setContentView(buildUi())
        loadPrefs()
        RuntimeState.settings = currentSettings()
        RuntimeState.addListener(stateListener)
        analyzeRide(silent = true)
    }

    override fun onResume() {
        super.onResume()
        populateBondedDevices()
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false
            startPanel()
        }
        if (::panelStatus.isInitialized) {
            panelStatus.text = if (Settings.canDrawOverlays(this)) {
                "✓ Permissão da janela concedida"
            } else "Permissão da janela ainda não concedida"
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
            text = "v1.0.0 — reconstruído sobre o motor de captura que funcionou na v0.2"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("USO NA RUA — FLUXO NOVO"))
        root.addView(TextView(this).apply {
            text = "1) Ative a JANELA. 2) Abra a Uber. 3) Já dentro da Uber, toque ATIVAR LEITURA na janela. 4) Autorize a captura e volte para a Uber. 5) Quando surgir a oferta, toque ANALISAR. Assim a captura é iniciada com a Uber já aberta, como no fluxo que funcionou anteriormente."
            textSize = 14f
        })

        root.addView(Button(this).apply {
            text = "1. ATIVAR JANELA FLUTUANTE"
            setOnClickListener { startPanel() }
        }, full(top = 8))

        panelStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(5), 0, dp(4))
        }
        root.addView(panelStatus)

        root.addView(Button(this).apply {
            text = "2. ABRIR UBER"
            setOnClickListener { openUber() }
        }, full(top = 4))

        captureStatus = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(captureStatus)

        root.addView(Button(this).apply {
            text = "ENCERRAR TURNO"
            setOnClickListener { stopShift() }
        }, full(top = 6))

        root.addView(TextView(this).apply {
            text = "A janela fica aberta sobre a Uber. Não há Acessibilidade, não há leitura contínua e não existe botão REATIVAR. Se a captura for encerrada pelo Android, a própria janela volta a mostrar ATIVAR LEITURA."
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })

        root.addView(section("TESTE MANUAL"))
        fare = field(root, "Valor da corrida (R$)", "27,50")
        pickup = field(root, "Km até o passageiro", "3")
        ride = field(root, "Km da viagem", "12")
        minutes = field(root, "Tempo total estimado (min)", "28")

        root.addView(Button(this).apply {
            text = "ANALISAR TESTE"
            setOnClickListener { analyzeRide() }
        }, full())

        result = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 35, 35))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(result, full(top = 10))

        root.addView(section("SEUS PARÂMETROS"))
        fuel = field(root, "Gasolina (R$/L)", "6,88")
        baseConsumption = field(root, "Consumo base do carro (km/L)", "12,6")
        bestConsumption = field(root, "Melhor consumo realista (km/L)", "13,5")
        netPerKmTarget = field(root, "Meta BOA após gasolina (R$/km total)", "1,25")
        root.addView(TextView(this).apply {
            text = "O km total é: distância até buscar + distância da viagem. A faixa RAZOÁVEL começa em 80% das metas."
            textSize = 12f
        })
        netPerHourTarget = field(root, "Meta BOA após gasolina (R$/hora)", "35")
        speedLimit = field(root, "Teto de referência de velocidade (km/h)", "60")
        tankCapacity = field(root, "Capacidade do tanque (L) — opcional", "0")

        root.addView(Button(this).apply {
            text = "SALVAR PARÂMETROS"
            setOnClickListener { saveSettingsAndRecalculate() }
        }, full())

        root.addView(Button(this).apply {
            text = "📊 RELATÓRIO DO DIA / HISTÓRICO"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReportActivity::class.java)) }
        }, full(top = 8))

        root.addView(section("OBD2 BLUETOOTH — OPCIONAL"))
        root.addView(TextView(this).apply {
            text = "O OBD não é necessário para analisar ofertas. Ele só melhora a referência de consumo quando estiver conectado."
            textSize = 13f
        })
        obdSpinner = Spinner(this)
        root.addView(obdSpinner, full(top = 8))

        val obdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        obdRow.addView(Button(this).apply {
            text = "CONECTAR OBD"
            setOnClickListener { connectSelectedObd() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        obdRow.addView(Button(this).apply {
            text = "DESCONECTAR"
            setOnClickListener { ObdHub.disconnect() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(obdRow)

        obdStatus = TextView(this).apply {
            text = "OBD desconectado"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(obdStatus)

        return scroll
    }

    private fun startPanel() {
        RuntimeState.settings = currentSettings()
        savePrefs()
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Ative 'Exibir sobre outros apps'. Ao voltar, a janela será iniciada.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Janela ativa. Agora abra a Uber e ative a leitura pela própria janela.", Toast.LENGTH_LONG).show()
    }

    private fun stopShift() {
        stopService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        stopService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        RuntimeState.captureReady = false
        RuntimeState.captureStatus = "Leitura desligada"
        RuntimeState.captureConfidence = 0
        RuntimeState.notifyChanged()
        Toast.makeText(this, "Turno encerrado", Toast.LENGTH_SHORT).show()
    }

    private fun openUber() {
        val intent = packageManager.getLaunchIntentForPackage("com.ubercab.driver")
        if (intent == null) {
            Toast.makeText(this, "Uber Driver não encontrado neste aparelho.", Toast.LENGTH_LONG).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    private fun analyzeRide(silent: Boolean = false) {
        val input = RideInput(num(fare, 27.5), num(pickup, 3.0), num(ride, 12.0), num(minutes, 28.0))
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
        fuelPrice = num(fuel, 6.88),
        baseConsumptionKml = num(baseConsumption, 12.6),
        currentConsumptionKml = num(baseConsumption, 12.6),
        bestRealisticConsumptionKml = num(bestConsumption, 13.5),
        targetNetPerKm = num(netPerKmTarget, 1.25),
        targetNetPerHour = num(netPerHourTarget, 35.0),
        safetySpeedLimitKmh = num(speedLimit, 60.0),
        tankCapacityLiters = num(tankCapacity, 0.0)
    )

    private fun formatAnalysis(a: RideAnalysis): String {
        val title = when (a.grade) {
            RideGrade.GREEN -> "🟢 CORRIDA BOA"
            RideGrade.YELLOW -> "🟡 CORRIDA RAZOÁVEL"
            RideGrade.RED -> "🔴 CORRIDA RUIM"
        }
        return buildString {
            append("$title\n")
            append("Distância total: %.1f km\n".format(a.totalKm))
            append("Combustível estimado: R$ %.2f\n".format(a.fuelCost))
            append("Após gasolina: R$ %.2f\n".format(a.netValue))
            append("Bruto: R$ %.2f/km • após gasolina: R$ %.2f/km\n".format(a.grossPerKm, a.netPerKm))
            append("Após gasolina por hora: R$ %.2f/h\n".format(a.netPerHour))
            append("\n${a.reason}")
        }
    }

    private fun connectSelectedObd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            connectObdAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 500)
            return
        }
        populateBondedDevices()
        if (obdAddresses.isEmpty()) {
            Toast.makeText(this, "Nenhum OBD pareado encontrado", Toast.LENGTH_LONG).show()
            return
        }
        val pos = obdSpinner.selectedItemPosition.coerceIn(0, obdAddresses.lastIndex)
        ObdHub.connect(this, obdAddresses[pos])
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 500 && connectObdAfterPermission) {
            connectObdAfterPermission = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                populateBondedDevices()
                connectSelectedObd()
            } else Toast.makeText(this, "Bluetooth não autorizado. O OBD continua opcional.", Toast.LENGTH_SHORT).show()
        }
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
        obdSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf("Nenhum dispositivo pareado") else labels)
    }

    private fun savePrefs() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat("fuel", num(fuel, 6.88).toFloat())
            .putFloat("base", num(baseConsumption, 12.6).toFloat())
            .putFloat("best", num(bestConsumption, 13.5).toFloat())
            .putFloat("netkm", num(netPerKmTarget, 1.25).toFloat())
            .putFloat("neth", num(netPerHourTarget, 35.0).toFloat())
            .putFloat("limit", num(speedLimit, 60.0).toFloat())
            .putFloat("tank", num(tankCapacity, 0.0).toFloat())
            .apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        fuel.setText(decimal(p.getFloat("fuel", 6.88f).toDouble()))
        baseConsumption.setText(decimal(p.getFloat("base", 12.6f).toDouble()))
        bestConsumption.setText(decimal(p.getFloat("best", 13.5f).toDouble()))
        netPerKmTarget.setText(decimal(p.getFloat("netkm", 1.25f).toDouble()))
        netPerHourTarget.setText(decimal(p.getFloat("neth", 35f).toDouble()))
        speedLimit.setText(decimal(p.getFloat("limit", 60f).toDouble()))
        tankCapacity.setText(decimal(p.getFloat("tank", 0f).toDouble()))
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

    private fun decimal(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v).trimEnd('0')
    private fun num(e: EditText, fallback: Double): Double = e.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: fallback
    private fun full(top: Int = 2) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
