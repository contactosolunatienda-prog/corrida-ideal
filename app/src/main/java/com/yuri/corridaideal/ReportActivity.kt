package com.yuri.corridaideal

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Relatório do Dia"
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Relatório do Dia"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })

        val r = RideHistoryStore.reportFor(this)
        card("RESUMO", buildString {
            append("Corridas concluídas: ${r.completedCount}")
            if (r.activeCount > 0) append(" • em andamento: ${r.activeCount}")
            append("\nBruto: R$ %.2f".format(r.grossRevenue))
            append("\nCombustível: %.2f L • R$ %.2f".format(r.fuelLiters, r.fuelCost))
            append("\nLíquido após combustível: R$ %.2f".format(r.netRevenue))
            append("\nDistância real/estimada: %.1f km".format(r.totalKm))
            append("\nBusca planejada: %.1f km • viagem: %.1f km".format(r.pickupKmPlanned, r.rideKmPlanned))
            r.averageConsumptionKml?.let { append("\nConsumo médio: %.1f km/L".format(it)) }
            r.grossPerKm?.let { append("\nBruto médio: R$ %.2f/km".format(it)) }
            r.netPerKm?.let { append("\nLíquido médio: R$ %.2f/km".format(it)) }
            r.netPerHour?.let { append("\nLíquido médio: R$ %.2f/h".format(it)) }
        })

        card("DECISÕES DO APP", buildString {
            append("Ofertas analisadas: 🟢 ${r.analyzedGreen}  🟡 ${r.analyzedYellow}  🔴 ${r.analyzedRed}")
            append("\nAceitas: 🟢 ${r.acceptedGreen}  🟡 ${r.acceptedYellow}  🔴 ${r.acceptedRed}")
            if (r.recommendedCompleted > 0) {
                val pct = 100.0 * r.recommendedHitGreenTarget / r.recommendedCompleted
                append("\nIndicações verde/amarela que terminaram batendo a meta verde: ${r.recommendedHitGreenTarget}/${r.recommendedCompleted} (%.0f%%)".format(pct))
            } else append("\nAinda não há corridas recomendadas concluídas para validar as indicações.")
        })

        if (r.estimatedRangeKm != null && r.remainingFuelLiters != null) {
            card("COMBUSTÍVEL / AUTONOMIA", buildString {
                append("Combustível estimado restante: %.1f L".format(r.remainingFuelLiters))
                append("\nValor do combustível restante: R$ %.2f".format(r.remainingFuelValue ?: 0.0))
                append("\nAutonomia estimada: %.0f km".format(r.estimatedRangeKm))
                append("\nEstimativa depende do PID de nível do tanque e da capacidade configurada.")
            })
        }

        root.addView(TextView(this).apply {
            text = "ÚLTIMAS CORRIDAS"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        })
        val records = RideHistoryStore.recentRecords(this, 20)
        if (records.isEmpty()) {
            root.addView(TextView(this).apply { text = "Nenhuma corrida registrada ainda."; textSize = 14f })
        } else {
            records.forEach { record ->
                val icon = when (record.gradeAtOffer) { RideGrade.GREEN -> "🟢"; RideGrade.YELLOW -> "🟡"; RideGrade.RED -> "🔴" }
                val status = when (record.status) { RideRecordStatus.ACCEPTED -> "EM ANDAMENTO"; RideRecordStatus.COMPLETED -> "CONCLUÍDA"; RideRecordStatus.CANCELLED -> "CANCELADA" }
                val txt = buildString {
                    append("$icon R$ %.2f • $status".format(record.offer.fare))
                    append("\nOferta: %.1f km busca + %.1f km viagem • %.0f min".format(record.offer.pickupKm, record.offer.rideKm, record.offer.estimatedMinutes))
                    if (record.status == RideRecordStatus.COMPLETED) {
                        append("\nReal: %.1f km • %.0f min".format(record.actualDistanceKm ?: record.offer.totalKm, record.actualMinutes ?: record.offer.estimatedMinutes))
                        record.averageConsumptionKml?.let { append(" • %.1f km/L".format(it)) }
                        append("\nBruto/km R$ %.2f • líquido/km R$ %.2f • líquido/h R$ %.0f".format(
                            record.actualGrossPerKm ?: 0.0, record.actualNetPerKm ?: 0.0, record.actualNetPerHour ?: 0.0
                        ))
                    }
                }
                card(null, txt)
            }
        }

        root.addView(Button(this).apply {
            text = "VOLTAR"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }

    private fun card(title: String?, body: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(238, 238, 238))
            gravity = Gravity.START
        }
        title?.let {
            box.addView(TextView(this).apply { text = it; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        }
        box.addView(TextView(this).apply { text = body; textSize = 15f; setPadding(0, if (title != null) dp(4) else 0, 0, 0) })
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
