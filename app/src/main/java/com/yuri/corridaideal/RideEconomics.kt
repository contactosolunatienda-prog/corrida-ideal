package com.yuri.corridaideal

import kotlin.math.max

object RideEconomics {
    fun analyze(input: RideInput, settings: AppSettings): RideAnalysis {
        val d = input.totalKm
        val mins = input.estimatedMinutes
        if (d <= 0.0 || input.fare <= 0.0 || mins <= 0.0) {
            return RideAnalysis(
                RideGrade.RED, d, 0.0, input.fare, 0.0, 0.0, 0.0,
                null, null, null, null, "Dados insuficientes da corrida."
            )
        }

        val currentC = settings.currentConsumptionKml.coerceAtLeast(0.1)
        val fuelCost = d / currentC * settings.fuelPrice
        val net = input.fare - fuelCost
        val grossPerKm = input.fare / d
        val netPerKm = net / d
        val netPerHour = net / (mins / 60.0)

        // First requirement: enough fuel economy to satisfy the net R$/km goal.
        val minConsumption = requiredConsumptionForNetPerKm(input, settings)

        // Build one concrete pair for the driver: use their normal/base economy
        // when that is already enough; otherwise use the minimum required economy.
        val pairConsumption = when {
            minConsumption == null || !minConsumption.isFinite() -> null
            else -> max(settings.baseConsumptionKml, minConsumption)
        }

        // Given that consumption, how fast must the total displacement be on average
        // to satisfy the net R$/hour goal? This is NOT an instruction to speed.
        val pairNet = pairConsumption?.let { c -> input.fare - (d / c * settings.fuelPrice) }
        val pairAverageSpeed = if (pairNet != null && pairNet > 0.0 && settings.targetNetPerHour > 0.0) {
            d * settings.targetNetPerHour / pairNet
        } else null
        val pairMaxMinutes = if (pairAverageSpeed != null && pairAverageSpeed > 0.0) {
            60.0 * d / pairAverageSpeed
        } else null

        val meetsNow = netPerKm >= settings.targetNetPerKm && netPerHour >= settings.targetNetPerHour
        val pairPlausible = pairConsumption != null && pairAverageSpeed != null &&
            pairConsumption <= settings.bestRealisticConsumptionKml &&
            pairAverageSpeed <= settings.safetySpeedLimitKmh

        val grade = when {
            meetsNow -> RideGrade.GREEN
            net <= 0.0 -> RideGrade.RED
            pairPlausible -> RideGrade.YELLOW
            else -> RideGrade.RED
        }

        val reason = when (grade) {
            RideGrade.GREEN -> "Bate suas metas atuais de lucro líquido por km e por hora."
            RideGrade.YELLOW -> "Pode virar verde mantendo cerca de %.1f km/L e média de deslocamento de aproximadamente %.0f km/h. A média é uma referência econômica/temporal, nunca autorização para exceder o limite da via."
                .format(pairConsumption, pairAverageSpeed)
            RideGrade.RED -> "O par necessário de consumo e média de deslocamento fica fora do cenário que você configurou como realista."
        }

        return RideAnalysis(
            grade = grade,
            totalKm = d,
            fuelCost = fuelCost,
            netValue = net,
            grossPerKm = grossPerKm,
            netPerKm = netPerKm,
            netPerHour = netPerHour,
            requiredConsumptionKml = pairConsumption,
            requiredAverageSpeedKmh = pairAverageSpeed,
            minimumConsumptionForKmTargetKml = minConsumption,
            maxMinutesForTarget = pairMaxMinutes,
            reason = reason
        )
    }

    private fun requiredConsumptionForNetPerKm(input: RideInput, s: AppSettings): Double? {
        val d = input.totalKm
        val denominator = input.fare - (s.targetNetPerKm * d)
        if (denominator <= 0) return Double.POSITIVE_INFINITY
        return (d * s.fuelPrice) / denominator
    }
}
