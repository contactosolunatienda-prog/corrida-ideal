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
        val baseC = settings.baseConsumptionKml.coerceAtLeast(0.1)
        val fuelCost = d / currentC * settings.fuelPrice
        val net = input.fare - fuelCost
        val grossPerKm = input.fare / d
        val netPerKm = net / d
        val netPerHour = net / (mins / 60.0)

        // Green requires a healthy gross R$/km AND the desired net hourly return.
        // Gross R$/km is intentionally independent of fuel economy.
        val greenGrossTarget = settings.targetGrossPerKm.coerceAtLeast(0.01)
        val greenHourTarget = settings.targetNetPerHour.coerceAtLeast(0.01)
        val yellowGrossFloor = greenGrossTarget * 0.80
        val yellowHourFloor = greenHourTarget * 0.80

        // At the ETA currently shown on the offer, what fuel economy would be
        // necessary to reach the green hourly target?
        val targetNetAtEta = greenHourTarget * (mins / 60.0)
        val availableForFuelAtEta = input.fare - targetNetAtEta
        val requiredConsumption = if (availableForFuelAtEta > 0.0) {
            (d * settings.fuelPrice) / availableForFuelAtEta
        } else Double.POSITIVE_INFINITY

        // At the driver's normal/base fuel economy, how much time can the whole
        // displacement take while still reaching the hourly green target?
        val baseFuelCost = d / baseC * settings.fuelPrice
        val baseNet = input.fare - baseFuelCost
        val maxMinutes = if (baseNet > 0.0) 60.0 * baseNet / greenHourTarget else null
        val requiredAverageSpeed = if (maxMinutes != null && maxMinutes > 0.0) {
            60.0 * d / maxMinutes
        } else null

        val meetsGreen = grossPerKm >= greenGrossTarget && netPerHour >= greenHourTarget
        val canReachHourlyWithEconomy = requiredConsumption.isFinite() &&
            requiredConsumption <= settings.bestRealisticConsumptionKml
        val canReachHourlyAtBase = requiredAverageSpeed != null &&
            requiredAverageSpeed <= settings.safetySpeedLimitKmh
        val greenEconomicallyPlausible = grossPerKm >= greenGrossTarget &&
            (canReachHourlyWithEconomy || canReachHourlyAtBase)

        val meetsYellowFloor = grossPerKm >= yellowGrossFloor && netPerHour >= yellowHourFloor

        val grade = when {
            meetsGreen -> RideGrade.GREEN
            net <= 0.0 -> RideGrade.RED
            greenEconomicallyPlausible -> RideGrade.YELLOW
            meetsYellowFloor -> RideGrade.YELLOW
            else -> RideGrade.RED
        }

        val reason = when (grade) {
            RideGrade.GREEN ->
                "Bate a meta verde de valor bruto por km e também a meta líquida por hora."

            RideGrade.YELLOW -> when {
                grossPerKm < greenGrossTarget ->
                    "É uma corrida intermediária: o valor bruto por km está abaixo da meta verde de R$ %.2f/km, mas ainda dentro da faixa amarela. Melhorar o consumo reduz custo, porém não altera o R$/km bruto."
                        .format(greenGrossTarget)
                else ->
                    "O valor bruto por km já é bom, mas o retorno por hora ainda depende do trânsito/tempo e do consumo. A média calculada é apenas referência econômica e nunca substitui o limite legal da via."
            }

            RideGrade.RED -> when {
                grossPerKm < yellowGrossFloor ->
                    "Valor bruto muito baixo: R$ %.2f/km. A faixa amarela começa perto de R$ %.2f/km e a verde em R$ %.2f/km. Melhor consumo sozinho não corrige uma oferta com R$/km bruto baixo."
                        .format(grossPerKm, yellowGrossFloor, greenGrossTarget)
                netPerHour < yellowHourFloor ->
                    "Retorno por hora muito baixo no tempo estimado da oferta: R$ %.2f/h líquidos. A faixa amarela começa perto de R$ %.0f/h."
                        .format(netPerHour, yellowHourFloor)
                else -> "A oferta ficou abaixo dos parâmetros econômicos configurados."
            }
        }

        // Only present 'how to turn green' targets when the gross R$/km already
        // reaches the green threshold. Otherwise fuel/speed cannot fix that metric.
        val showGreenPair = !meetsGreen && grossPerKm >= greenGrossTarget

        return RideAnalysis(
            grade = grade,
            totalKm = d,
            fuelCost = fuelCost,
            netValue = net,
            grossPerKm = grossPerKm,
            netPerKm = netPerKm,
            netPerHour = netPerHour,
            requiredConsumptionKml = if (showGreenPair) requiredConsumption else null,
            requiredAverageSpeedKmh = if (showGreenPair) requiredAverageSpeed else null,
            maxMinutesForTarget = if (showGreenPair) maxMinutes else null,
            reason = reason
        )
    }
}
