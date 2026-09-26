package com.yuri.corridaideal

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.accessibility.AccessibilityManager
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
    private lateinit var readStatus: TextView
    private lateinit var startButton: Button
    private lateinit var trackingStatus: TextView
    private val obdAddresses = mutableListOf<String>()
    private var connectObdAfterPermission = false

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
            if (::trackingStatus.isInitialized) trackingStatus.text = snap.trackingStatus
            refreshAccessibilityStatus()
            if (snap.captureConfidence > 0 && ::fare.isInitialized) {
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
        RuntimeState.settings = currentSettings()
        RuntimeState.addListener(stateListener)
        analyzeRide(silent = true)
    }

    override fun onResume() {
        super.onResume()
        populateBondedDevices()
        refreshAccessibilityStatus()
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
            text = "v0.6.0 — novo motor sem gravação de tela"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("ATIVAÇÃO — FAZER UMA ÚNICA VEZ"))
        root.addView(TextView(this).apply {
            text = "Esta versão NÃO usa gravar/compartilhar tela. Ative o serviço Corrida Ideal em Acessibilidade uma vez. Depois abra a Uber normalmente. A bolha ANALISAR aparece sobre a Uber e continua funcionando mesmo com o Corrida Ideal fechado. Ela só tira uma captura quando você toca nela e não aceita nem recusa corrida."
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })

        startButton = Button(this).apply {
            text = "ATIVAR LEITURA EM ACESSIBILIDADE"
            setOnClickListener {
                if (isAccessibilityEnabled()) openUber() else openAccessibilitySettings()
            }
        }
        root.addView(startButton, full())

        root.addView(Button(this).apply {
            text = "SE ESTIVER BLOQUEADO: ABRIR INFORMAÇÕES DO APP"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                Toast.makeText(
                    this@MainActivity,
                    "No menu ⋮, toque em 'Permitir configurações restritas'. Depois volte e ative em Acessibilidade.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, full(top = 6))

        readStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(readStatus)

        root.addView(Button(this).apply {
            text = "ABRIR UBER"
            setOnClickListener { openUber() }
        }, full(top = 6))

        root.addView(TextView(this).apply {
            text = "Uso na rua: abra a Uber → espere a oferta aparecer → toque uma vez em ANALISAR. Resultado: 🟢 BOA / 🟡 RAZOÁVEL / 🔴 RUIM + voz curta. Não existe mais botão REATIVAR nem autorização de gravação de tela."
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })

        trackingStatus = TextView(this).apply {
            text = RuntimeState.trackingStatus
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(trackingStatus)

        root.addView(Button(this).apply {
            text = "📊 RELATÓRIO DO DIA / ÚLTIMAS CORRIDAS"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReportActivity::class.java)) }
        }, full(top = 6))

        root.addView(section("TESTE MANUAL / CONFERÊNCIA"))
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
            text = "O km total é busca + viagem. A faixa RAZOÁVEL começa em 80% das metas."
            textSize = 12f
        })
        netPerHourTarget = field(root, "Meta BOA após gasolina (R$/hora)", "35")
        speedLimit = field(root, "Teto de referência de velocidade (km/h)", "60")
        tankCapacity = field(root, "Capacidade do tanque (L) — opcional", "0")

        root.addView(Button(this).apply {
            text = "SALVAR PARÂMETROS"
            setOnClickListener { saveSettingsAndRecalculate() }
        }, full())

        root.addView(section("OBD2 BLUETOOTH — OPCIONAL"))
        root.addView(TextView(this).apply {
            text = "O OBD não é necessário para analisar a oferta. Ele serve para melhorar o consumo e os relatórios."
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

    private fun refreshAccessibilityStatus() {
        if (!::readStatus.isInitialized || !::startButton.isInitialized) return
        val enabled = isAccessibilityEnabled()
        val running = UberAccessibilityService.instance != null
        readStatus.text = when {
            running -> "✓ LEITURA ATIVA — abra a Uber. A bolha aparece quando a Uber estiver na frente."
            enabled -> "✓ ACESSIBILIDADE AUTORIZADA — abra a Uber. O serviço será iniciado pelo Android."
            else -> "LEITURA DESATIVADA — ative 'Corrida Ideal' nas configurações de Acessibilidade."
        }
        startButton.text = if (enabled) "✓ LEITURA ATIVA — ABRIR UBER" else "ATIVAR LEITURA EM ACESSIBILIDADE"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val service = info.resolveInfo?.serviceInfo
            service?.packageName == packageName &&
                (service.name == UberAccessibilityService::class.java.name || service.name.endsWith(".UberAccessibilityService"))
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Procure Corrida Ideal e ative o serviço.", Toast.LENGTH_LONG).show()
    }

    private fun openUber() {
        val intent = packageManager.getLaunchIntentForPackage(UberAccessibilityService.UBER_PACKAGE)
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
            } else {
                Toast.makeText(this, "Bluetooth não autorizado. O OBD continua opcional.", Toast.LENGTH_SHORT).show()
            }
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
        obdSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf("Nenhum dispositivo pareado") else labels
        )
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
        // Migração: versões antigas salvavam meta bruta em grosskm. A nova versão
        // começa com a meta líquida desejada de R$ 1,25/km.
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
