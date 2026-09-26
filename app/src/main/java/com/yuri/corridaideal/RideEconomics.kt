package com.yuri.corridaideal

object RideEconomics {
    fun analyze(input: RideInput, settings: AppSettings): RideAnalysis {
        val d = input.totalKm
        val mins = input.estimatedMinutes
        if (d <= 0.0 || input.fare <= 0.0 || mins <= 0.0) {
            return RideAnalysis(
                RideGrade.RED, d, 0.0, input.fare, 0.0, 0.0, 0.0,
                null, null, null, "Dados insuficientes da corrida."
            )
        }

        val currentC = settings.currentConsumptionKml.coerceAtLeast(0.1)
        val fuelCost = d / currentC * settings.fuelPrice
        val net = input.fare - fuelCost
        val grossPerKm = input.fare / d
        val netPerKm = net / d
        val netPerHour = net / (mins / 60.0)

        val kmTarget = settings.targetNetPerKm.coerceAtLeast(0.01)
        val hourTarget = settings.targetNetPerHour.coerceAtLeast(0.01)
        val yellowKm = kmTarget * 0.80
        val yellowHour = hourTarget * 0.80

        val grade = when {
            netPerKm >= kmTarget && netPerHour >= hourTarget -> RideGrade.GREEN
            net <= 0.0 -> RideGrade.RED
            netPerKm >= yellowKm && netPerHour >= yellowHour -> RideGrade.YELLOW
            else -> RideGrade.RED
        }

        // Apenas referências informativas para a tela detalhada/relatório.
        val maxFuelCostForKmTarget = input.fare - (kmTarget * d)
        val requiredConsumption = if (maxFuelCostForKmTarget > 0.0) {
            (d * settings.fuelPrice) / maxFuelCostForKmTarget
        } else Double.POSITIVE_INFINITY

        val maxMinutes = if (net > 0.0) 60.0 * net / hourTarget else null
        val requiredAverageSpeed = if (maxMinutes != null && maxMinutes > 0.0) {
            60.0 * d / maxMinutes
        } else null

        val reason = when (grade) {
            RideGrade.GREEN ->
                "Depois do combustível, bate R$ %.2f/km e R$ %.0f/h ou mais.".format(kmTarget, hourTarget)
            RideGrade.YELLOW ->
                "Ficou perto da meta: líquido R$ %.2f/km e R$ %.0f/h. Meta BOA: R$ %.2f/km e R$ %.0f/h."
                    .format(netPerKm, netPerHour, kmTarget, hourTarget)
            RideGrade.RED ->
                "Ficou abaixo da faixa mínima: líquido R$ %.2f/km e R$ %.0f/h."
                    .format(netPerKm, netPerHour)
        }

        return RideAnalysis(
            grade = grade,
            totalKm = d,
            fuelCost = fuelCost,
            netValue = net,
            grossPerKm = grossPerKm,
            netPerKm = netPerKm,
            netPerHour = netPerHour,
            requiredConsumptionKml = requiredConsumption.takeIf { it.isFinite() },
            requiredAverageSpeedKmh = requiredAverageSpeed,
            maxMinutesForTarget = maxMinutes,
            reason = reason
        )
    }
}
