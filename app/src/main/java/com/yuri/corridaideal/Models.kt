package com.yuri.corridaideal

data class RideInput(
    val fare: Double,
    val pickupKm: Double,
    val rideKm: Double,
    val estimatedMinutes: Double
) {
    val totalKm: Double get() = pickupKm + rideKm
}

data class AppSettings(
    val fuelPrice: Double = 6.88,
    val baseConsumptionKml: Double = 12.6,
    val currentConsumptionKml: Double = 12.6,
    val bestRealisticConsumptionKml: Double = 13.5,
    /** Meta principal: quanto deve sobrar por km depois do combustível estimado. */
    val targetNetPerKm: Double = 1.25,
    /** Meta de retorno por hora depois do combustível estimado. */
    val targetNetPerHour: Double = 35.0,
    val safetySpeedLimitKmh: Double = 60.0,
    val tankCapacityLiters: Double = 0.0
)

enum class RideGrade { GREEN, YELLOW, RED }

data class RideAnalysis(
    val grade: RideGrade,
    val totalKm: Double,
    val fuelCost: Double,
    val netValue: Double,
    val grossPerKm: Double,
    val netPerKm: Double,
    val netPerHour: Double,
    val requiredConsumptionKml: Double?,
    val requiredAverageSpeedKmh: Double?,
    val maxMinutesForTarget: Double?,
    val reason: String
)

enum class RideRecordStatus { ACCEPTED, COMPLETED, CANCELLED }

data class RideRecord(
    val id: String,
    val dateKey: String,
    val offer: RideInput,
    val gradeAtOffer: RideGrade,
    val analyzedAt: Long,
    val acceptedAt: Long,
    val completedAt: Long? = null,
    val status: RideRecordStatus = RideRecordStatus.ACCEPTED,
    val plannedGrossPerKm: Double,
    val plannedNetPerKm: Double,
    val plannedNetPerHour: Double,
    val plannedFuelCost: Double,
    val actualDistanceKm: Double? = null,
    val actualMinutes: Double? = null,
    val averageConsumptionKml: Double? = null,
    val actualFuelLiters: Double? = null,
    val actualFuelCost: Double? = null,
    val actualNetValue: Double? = null,
    val actualGrossPerKm: Double? = null,
    val actualNetPerKm: Double? = null,
    val actualNetPerHour: Double? = null,
    val acceptanceSource: String = "manual"
)

data class DailyReport(
    val dateKey: String,
    val analyzedGreen: Int,
    val analyzedYellow: Int,
    val analyzedRed: Int,
    val acceptedGreen: Int,
    val acceptedYellow: Int,
    val acceptedRed: Int,
    val completedCount: Int,
    val activeCount: Int,
    val grossRevenue: Double,
    val totalKm: Double,
    val pickupKmPlanned: Double,
    val rideKmPlanned: Double,
    val fuelLiters: Double,
    val fuelCost: Double,
    val netRevenue: Double,
    val workMinutes: Double,
    val averageConsumptionKml: Double?,
    val grossPerKm: Double?,
    val netPerKm: Double?,
    val netPerHour: Double?,
    val recommendedCompleted: Int,
    val recommendedHitGreenTarget: Int,
    val remainingFuelLiters: Double?,
    val estimatedRangeKm: Double?,
    val remainingFuelValue: Double?
)
