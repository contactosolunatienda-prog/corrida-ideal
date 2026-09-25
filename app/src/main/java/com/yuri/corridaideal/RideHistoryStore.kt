package com.yuri.corridaideal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RideHistoryStore {
    private const val PREFS = "ride_history_v1"
    private const val KEY_RECORDS = "records"
    private const val KEY_PENDING = "pending_offer"
    private const val COUNTERS = "daily_offer_counters"

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun onOfferAnalyzed(context: Context, ride: RideInput, analysis: RideAnalysis) {
        val signature = signature(ride)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (old?.optString("signature") == signature) return

        val pending = JSONObject()
            .put("signature", signature)
            .put("analyzedAt", System.currentTimeMillis())
            .put("fare", ride.fare)
            .put("pickupKm", ride.pickupKm)
            .put("rideKm", ride.rideKm)
            .put("minutes", ride.estimatedMinutes)
            .put("grade", analysis.grade.name)
            .put("grossPerKm", analysis.grossPerKm)
            .put("netPerKm", analysis.netPerKm)
            .put("netPerHour", analysis.netPerHour)
            .put("fuelCost", analysis.fuelCost)
        prefs.edit().putString(KEY_PENDING, pending.toString()).apply()
        incrementAnalyzed(context, analysis.grade)
        RuntimeState.trackingStatus = "Oferta analisada — aguardando sua decisão"
        RuntimeState.notifyChanged()
    }

    fun pendingOffer(context: Context): JSONObject? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun confirmAccepted(context: Context, source: String = "manual"): RideRecord? {
        if (activeRecord(context) != null) return activeRecord(context)
        val pending = pendingOffer(context) ?: return null
        val now = System.currentTimeMillis()
        val ride = RideInput(
            pending.optDouble("fare"), pending.optDouble("pickupKm"),
            pending.optDouble("rideKm"), pending.optDouble("minutes")
        )
        val record = RideRecord(
            id = now.toString(),
            dateKey = todayKey(),
            offer = ride,
            gradeAtOffer = runCatching { RideGrade.valueOf(pending.optString("grade")) }.getOrDefault(RideGrade.YELLOW),
            analyzedAt = pending.optLong("analyzedAt", now),
            acceptedAt = now,
            plannedGrossPerKm = pending.optDouble("grossPerKm"),
            plannedNetPerKm = pending.optDouble("netPerKm"),
            plannedNetPerHour = pending.optDouble("netPerHour"),
            plannedFuelCost = pending.optDouble("fuelCost"),
            acceptanceSource = source
        )
        val records = loadRecords(context).toMutableList()
        records.add(record)
        saveRecords(context, records)
        RuntimeState.activeRideRecordId = record.id
        RuntimeState.trackingStatus = "Corrida aceita — acompanhamento iniciado"
        RuntimeState.notifyChanged()
        return record
    }

    fun rejectPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply()
        RuntimeState.trackingStatus = "Oferta descartada"
        RuntimeState.notifyChanged()
    }

    fun activeRecord(context: Context): RideRecord? = loadRecords(context).lastOrNull { it.status == RideRecordStatus.ACCEPTED }

    fun completeActive(
        context: Context,
        actualDistanceKm: Double,
        actualMinutes: Double,
        averageConsumptionKml: Double?,
        settings: AppSettings,
        completedAt: Long = System.currentTimeMillis()
    ): RideRecord? {
        val records = loadRecords(context).toMutableList()
        val idx = records.indexOfLast { it.status == RideRecordStatus.ACCEPTED }
        if (idx < 0) return null
        val old = records[idx]
        val distance = actualDistanceKm.takeIf { it > 0.05 } ?: old.offer.totalKm
        val minutes = actualMinutes.takeIf { it > 0.5 } ?: old.offer.estimatedMinutes
        val consumption = (averageConsumptionKml ?: settings.currentConsumptionKml).coerceAtLeast(0.1)
        val liters = distance / consumption
        val fuelCost = liters * settings.fuelPrice
        val net = old.offer.fare - fuelCost
        val grossPerKm = old.offer.fare / distance
        val netPerKm = net / distance
        val netPerHour = net / (minutes / 60.0)
        val updated = old.copy(
            completedAt = completedAt,
            status = RideRecordStatus.COMPLETED,
            actualDistanceKm = distance,
            actualMinutes = minutes,
            averageConsumptionKml = consumption,
            actualFuelLiters = liters,
            actualFuelCost = fuelCost,
            actualNetValue = net,
            actualGrossPerKm = grossPerKm,
            actualNetPerKm = netPerKm,
            actualNetPerHour = netPerHour
        )
        records[idx] = updated
        saveRecords(context, records)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply()
        RuntimeState.activeRideRecordId = null
        RuntimeState.trackingStatus = "Corrida finalizada e salva no relatório"
        RuntimeState.notifyChanged()
        return updated
    }

    fun cancelActive(context: Context) {
        val records = loadRecords(context).toMutableList()
        val idx = records.indexOfLast { it.status == RideRecordStatus.ACCEPTED }
        if (idx >= 0) {
            records[idx] = records[idx].copy(status = RideRecordStatus.CANCELLED, completedAt = System.currentTimeMillis())
            saveRecords(context, records)
        }
        RuntimeState.activeRideRecordId = null
        RuntimeState.trackingStatus = "Corrida cancelada"
        RuntimeState.notifyChanged()
    }

    fun reportFor(context: Context, dateKey: String = todayKey()): DailyReport {
        val settings = RuntimeState.settings
        val records = loadRecords(context).filter { it.dateKey == dateKey && it.status != RideRecordStatus.CANCELLED }
        val completed = records.filter { it.status == RideRecordStatus.COMPLETED }
        val counters = counters(context, dateKey)
        val gross = completed.sumOf { it.offer.fare }
        val km = completed.sumOf { it.actualDistanceKm ?: it.offer.totalKm }
        val pickup = completed.sumOf { it.offer.pickupKm }
        val rideKm = completed.sumOf { it.offer.rideKm }
        val liters = completed.sumOf { it.actualFuelLiters ?: ((it.actualDistanceKm ?: it.offer.totalKm) / (it.averageConsumptionKml ?: settings.baseConsumptionKml)) }
        val fuelCost = completed.sumOf { it.actualFuelCost ?: 0.0 }
        val net = completed.sumOf { it.actualNetValue ?: (it.offer.fare - (it.actualFuelCost ?: 0.0)) }
        val mins = completed.sumOf { it.actualMinutes ?: it.offer.estimatedMinutes }
        val avgConsumption = if (liters > 0.0) km / liters else null
        val grossPerKm = if (km > 0.0) gross / km else null
        val netPerKm = if (km > 0.0) net / km else null
        val netPerHour = if (mins > 0.0) net / (mins / 60.0) else null
        val recommended = completed.filter { it.gradeAtOffer != RideGrade.RED }
        val hit = recommended.count {
            (it.actualGrossPerKm ?: 0.0) >= settings.targetGrossPerKm &&
                (it.actualNetPerHour ?: 0.0) >= settings.targetNetPerHour
        }
        val fuelPct = RuntimeState.fuelLevelPercent
        val remainingLiters = if (fuelPct != null && settings.tankCapacityLiters > 0.0) settings.tankCapacityLiters * fuelPct / 100.0 else null
        val range = if (remainingLiters != null) remainingLiters * (avgConsumption ?: settings.baseConsumptionKml) else null
        val fuelValue = remainingLiters?.times(settings.fuelPrice)
        return DailyReport(
            dateKey = dateKey,
            analyzedGreen = counters.first,
            analyzedYellow = counters.second,
            analyzedRed = counters.third,
            acceptedGreen = records.count { it.gradeAtOffer == RideGrade.GREEN },
            acceptedYellow = records.count { it.gradeAtOffer == RideGrade.YELLOW },
            acceptedRed = records.count { it.gradeAtOffer == RideGrade.RED },
            completedCount = completed.size,
            activeCount = records.count { it.status == RideRecordStatus.ACCEPTED },
            grossRevenue = gross,
            totalKm = km,
            pickupKmPlanned = pickup,
            rideKmPlanned = rideKm,
            fuelLiters = liters,
            fuelCost = fuelCost,
            netRevenue = net,
            workMinutes = mins,
            averageConsumptionKml = avgConsumption,
            grossPerKm = grossPerKm,
            netPerKm = netPerKm,
            netPerHour = netPerHour,
            recommendedCompleted = recommended.size,
            recommendedHitGreenTarget = hit,
            remainingFuelLiters = remainingLiters,
            estimatedRangeKm = range,
            remainingFuelValue = fuelValue
        )
    }

    fun recentRecords(context: Context, limit: Int = 30): List<RideRecord> = loadRecords(context)
        .sortedByDescending { it.acceptedAt }.take(limit)

    private fun incrementAnalyzed(context: Context, grade: RideGrade) {
        val key = todayKey() + "_" + grade.name
        val prefs = context.getSharedPreferences(COUNTERS, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun counters(context: Context, dateKey: String): Triple<Int, Int, Int> {
        val p = context.getSharedPreferences(COUNTERS, Context.MODE_PRIVATE)
        return Triple(
            p.getInt("${dateKey}_${RideGrade.GREEN.name}", 0),
            p.getInt("${dateKey}_${RideGrade.YELLOW.name}", 0),
            p.getInt("${dateKey}_${RideGrade.RED.name}", 0)
        )
    }

    private fun signature(r: RideInput) = "%.2f|%.2f|%.2f|%.0f".format(r.fare, r.pickupKm, r.rideKm, r.estimatedMinutes)

    private fun loadRecords(context: Context): List<RideRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) decode(arr.getJSONObject(i))?.let(::add)
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecords(context: Context, records: List<RideRecord>) {
        val trimmed = records.sortedByDescending { it.acceptedAt }.take(300).reversed()
        val arr = JSONArray()
        trimmed.forEach { arr.put(encode(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    private fun encode(r: RideRecord) = JSONObject()
        .put("id", r.id).put("dateKey", r.dateKey)
        .put("fare", r.offer.fare).put("pickupKm", r.offer.pickupKm).put("rideKm", r.offer.rideKm).put("estimatedMinutes", r.offer.estimatedMinutes)
        .put("grade", r.gradeAtOffer.name).put("analyzedAt", r.analyzedAt).put("acceptedAt", r.acceptedAt)
        .put("completedAt", r.completedAt ?: JSONObject.NULL).put("status", r.status.name)
        .put("plannedGrossPerKm", r.plannedGrossPerKm).put("plannedNetPerKm", r.plannedNetPerKm)
        .put("plannedNetPerHour", r.plannedNetPerHour).put("plannedFuelCost", r.plannedFuelCost)
        .put("actualDistanceKm", r.actualDistanceKm ?: JSONObject.NULL).put("actualMinutes", r.actualMinutes ?: JSONObject.NULL)
        .put("averageConsumptionKml", r.averageConsumptionKml ?: JSONObject.NULL).put("actualFuelLiters", r.actualFuelLiters ?: JSONObject.NULL)
        .put("actualFuelCost", r.actualFuelCost ?: JSONObject.NULL).put("actualNetValue", r.actualNetValue ?: JSONObject.NULL)
        .put("actualGrossPerKm", r.actualGrossPerKm ?: JSONObject.NULL).put("actualNetPerKm", r.actualNetPerKm ?: JSONObject.NULL)
        .put("actualNetPerHour", r.actualNetPerHour ?: JSONObject.NULL).put("acceptanceSource", r.acceptanceSource)

    private fun decode(o: JSONObject): RideRecord? = runCatching {
        RideRecord(
            id = o.getString("id"), dateKey = o.getString("dateKey"),
            offer = RideInput(o.getDouble("fare"), o.getDouble("pickupKm"), o.getDouble("rideKm"), o.getDouble("estimatedMinutes")),
            gradeAtOffer = RideGrade.valueOf(o.getString("grade")), analyzedAt = o.getLong("analyzedAt"), acceptedAt = o.getLong("acceptedAt"),
            completedAt = o.optNullableDouble("completedAt")?.toLong(), status = RideRecordStatus.valueOf(o.getString("status")),
            plannedGrossPerKm = o.optDouble("plannedGrossPerKm"), plannedNetPerKm = o.optDouble("plannedNetPerKm"),
            plannedNetPerHour = o.optDouble("plannedNetPerHour"), plannedFuelCost = o.optDouble("plannedFuelCost"),
            actualDistanceKm = o.optNullableDouble("actualDistanceKm"), actualMinutes = o.optNullableDouble("actualMinutes"),
            averageConsumptionKml = o.optNullableDouble("averageConsumptionKml"), actualFuelLiters = o.optNullableDouble("actualFuelLiters"),
            actualFuelCost = o.optNullableDouble("actualFuelCost"), actualNetValue = o.optNullableDouble("actualNetValue"),
            actualGrossPerKm = o.optNullableDouble("actualGrossPerKm"), actualNetPerKm = o.optNullableDouble("actualNetPerKm"),
            actualNetPerHour = o.optNullableDouble("actualNetPerHour"), acceptanceSource = o.optString("acceptanceSource", "manual")
        )
    }.getOrNull()

    private fun JSONObject.optNullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)
}
