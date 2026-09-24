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
    val fuelPrice: Double = 6.98,
    val baseConsumptionKml: Double = 12.6,
    val currentConsumptionKml: Double = 12.6,
    val bestRealisticConsumptionKml: Double = 14.0,
    val targetNetPerKm: Double = 1.20,
    val targetNetPerHour: Double = 35.0,
    val safetySpeedLimitKmh: Double = 60.0
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
    /** Consumption used in the suggested "turn green" pair. */
    val requiredConsumptionKml: Double?,
    /** Average displacement speed used in the suggested "turn green" pair. */
    val requiredAverageSpeedKmh: Double?,
    /** Absolute minimum consumption needed to satisfy the net R$/km target. */
    val minimumConsumptionForKmTargetKml: Double?,
    val maxMinutesForTarget: Double?,
    val reason: String
)
