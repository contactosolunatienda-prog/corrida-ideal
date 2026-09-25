package com.yuri.corridaideal

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

data class LiveSnapshot(
    val ride: RideInput? = null,
    val settings: AppSettings = AppSettings(),
    val analysis: RideAnalysis? = null,
    val obdSpeedKmh: Double? = null,
    val obdConsumptionKml: Double? = null,
    val manualConsumptionOverrideKml: Double? = null,
    val gpsSpeedKmh: Double? = null,
    val tripAverageSpeedKmh: Double? = null,
    val obdConnected: Boolean = false,
    val obdStatus: String = "OBD desconectado",
    val captureReady: Boolean = false,
    val autoCaptureEnabled: Boolean = false,
    val captureStatus: String = "Leitura de tela desligada",
    val captureConfidence: Int = 0,
    val lastOcrText: String = "",
    val trackingStatus: String = "Nenhuma corrida em acompanhamento",
    val activeRideRecordId: String? = null,
    val activeTripDistanceKm: Double = 0.0,
    val activeTripElapsedMinutes: Double = 0.0,
    val activeTripAvgConsumptionKml: Double? = null,
    val fuelLevelPercent: Double? = null
)

object RuntimeState {
    @Volatile var ride: RideInput? = null
    @Volatile var settings: AppSettings = AppSettings()
    @Volatile var analysis: RideAnalysis? = null
    @Volatile var obdSpeedKmh: Double? = null
    @Volatile var obdConsumptionKml: Double? = null
    @Volatile var manualConsumptionOverrideKml: Double? = null
    @Volatile var gpsSpeedKmh: Double? = null
    @Volatile var tripAverageSpeedKmh: Double? = null
    @Volatile var obdConnected: Boolean = false
    @Volatile var obdStatus: String = "OBD desconectado"
    @Volatile var captureReady: Boolean = false
    @Volatile var autoCaptureEnabled: Boolean = false
    @Volatile var captureStatus: String = "Leitura de tela desligada"
    @Volatile var captureConfidence: Int = 0
    @Volatile var lastOcrText: String = ""
    @Volatile var trackingStatus: String = "Nenhuma corrida em acompanhamento"
    @Volatile var activeRideRecordId: String? = null
    @Volatile var activeTripDistanceKm: Double = 0.0
    @Volatile var activeTripElapsedMinutes: Double = 0.0
    @Volatile var activeTripAvgConsumptionKml: Double? = null
    @Volatile var fuelLevelPercent: Double? = null

    private val listeners = CopyOnWriteArrayList<(LiveSnapshot) -> Unit>()

    fun snapshot() = LiveSnapshot(
        ride, settings, analysis, obdSpeedKmh, obdConsumptionKml, manualConsumptionOverrideKml,
        gpsSpeedKmh, tripAverageSpeedKmh, obdConnected, obdStatus,
        captureReady, autoCaptureEnabled, captureStatus, captureConfidence, lastOcrText,
        trackingStatus, activeRideRecordId, activeTripDistanceKm, activeTripElapsedMinutes,
        activeTripAvgConsumptionKml, fuelLevelPercent
    )

    fun addListener(listener: (LiveSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (LiveSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        val snap = snapshot()
        listeners.forEach { runCatching { it(snap) } }
    }

    fun recalculate(consumptionOverride: Double? = null) {
        val r = ride ?: return
        val c = manualConsumptionOverrideKml ?: consumptionOverride ?: obdConsumptionKml ?: settings.currentConsumptionKml
        settings = settings.copy(currentConsumptionKml = c)
        analysis = RideEconomics.analyze(r, settings)
        notifyChanged()
    }
}

/**
 * Learns this specific car's observed efficiency by 5 km/h speed buckets.
 * This is intentionally empirical: the app does not assume a universal
 * "best speed" because traffic, gearing, slope, load and A/C matter.
 */
class EfficiencyProfile(private val context: Context) {
    private val prefs = context.getSharedPreferences("efficiency_profile", Context.MODE_PRIVATE)

    data class Bucket(var count: Int = 0, var sumKml: Double = 0.0) {
        val mean: Double get() = if (count > 0) sumKml / count else 0.0
    }

    fun record(speedKmh: Double, kml: Double) {
        if (speedKmh < 10.0 || speedKmh > 140.0 || kml < 2.0 || kml > 40.0) return
        val bucketStart = (floor(speedKmh / 5.0) * 5).toInt()
        val countKey = "count_$bucketStart"
        val sumKey = "sum_$bucketStart"
        val count = prefs.getInt(countKey, 0) + 1
        val sum = java.lang.Double.longBitsToDouble(prefs.getLong(sumKey, 0L)) + kml
        prefs.edit()
            .putInt(countKey, count)
            .putLong(sumKey, java.lang.Double.doubleToRawLongBits(sum))
            .apply()
    }

    fun rangesMeeting(requiredKml: Double, minSamples: Int = 8): String? {
        val qualifying = mutableListOf<Int>()
        for (start in 10..135 step 5) {
            val count = prefs.getInt("count_$start", 0)
            val sumBits = prefs.getLong("sum_$start", 0L)
            val sum = java.lang.Double.longBitsToDouble(sumBits)
            if (count >= minSamples && sum / count >= requiredKml) qualifying.add(start)
        }
        if (qualifying.isEmpty()) return null

        val groups = mutableListOf<IntRange>()
        var start = qualifying.first()
        var previous = start
        for (v in qualifying.drop(1)) {
            if (v == previous + 5) {
                previous = v
            } else {
                groups.add(start..(previous + 5))
                start = v
                previous = v
            }
        }
        groups.add(start..(previous + 5))
        return groups.joinToString(" ou ") { "${it.first}–${it.last} km/h" }
    }

    fun bestObservedRange(minSamples: Int = 8): String? {
        var bestStart: Int? = null
        var bestMean = 0.0
        for (start in 10..135 step 5) {
            val count = prefs.getInt("count_$start", 0)
            if (count < minSamples) continue
            val sum = java.lang.Double.longBitsToDouble(prefs.getLong("sum_$start", 0L))
            val mean = sum / count
            if (mean > bestMean) {
                bestMean = mean
                bestStart = start
            }
        }
        return bestStart?.let { "$it–${it + 5} km/h (≈ %.1f km/L observado)".format(bestMean) }
    }

    fun clear() = prefs.edit().clear().apply()
}
