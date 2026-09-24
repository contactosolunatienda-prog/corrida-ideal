package com.yuri.corridaideal

import java.text.Normalizer
import kotlin.math.max

/**
 * Heuristic parser for ride-offer text recognized from the screen.
 * It deliberately does not depend on private Uber APIs or internal view IDs.
 * The parser is conservative: when it cannot identify the essential fields,
 * it returns a partial result instead of inventing a green/red decision.
 */
data class ParsedScreenOffer(
    val ride: RideInput?,
    val fare: Double?,
    val pickupKm: Double?,
    val rideKm: Double?,
    val minutes: Double?,
    val confidence: Int,
    val message: String,
    val rawText: String
)

object ScreenOfferParser {
    private val moneyRegex = Regex("(?:R\\$|RS|S\\$)\\s*([0-9]{1,3}(?:[.,][0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val kmRegex = Regex("([0-9]{1,3}(?:[.,][0-9]{1,2})?)\\s*(?:km|quilometro|quilometros)", RegexOption.IGNORE_CASE)
    private val minRegex = Regex("([0-9]{1,3})\\s*(?:min|minuto|minutos)\\b", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedScreenOffer {
        val cleaned = raw.replace('•', ' ').replace('|', ' ')
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        val normalizedLines = lines.map { normalize(it) }

        val moneyValues = moneyRegex.findAll(cleaned)
            .mapNotNull { number(it.groupValues[1]) }
            .filter { it in 2.0..500.0 }
            .toList()
        val fare = moneyValues.maxOrNull()

        var pickupKm: Double? = null
        var rideKm: Double? = null
        var explicitTotalKm: Double? = null
        val distanceFallback = mutableListOf<Double>()
        val minuteFallback = mutableListOf<Double>()
        var pickupMin: Double? = null
        var rideMin: Double? = null

        lines.indices.forEach { i ->
            val line = lines[i]
            val n = normalizedLines[i]
            val distances = kmRegex.findAll(line).mapNotNull { number(it.groupValues[1]) }
                .filter { it in 0.1..300.0 }.toList()
            val minutes = minRegex.findAll(line).mapNotNull { number(it.groupValues[1]) }
                .filter { it in 1.0..300.0 }.toList()

            distanceFallback += distances
            minuteFallback += minutes

            val pickupContext = listOf("buscar", "embarque", "passageiro", "ate voce", "ate o passageiro", "distancia ate").any { n.contains(it) }
            val rideContext = listOf("viagem", "destino", "trajeto", "corrida", "para o destino").any { n.contains(it) }
            val totalContext = n.contains("total") && !n.contains("valor total")

            if (distances.isNotEmpty()) {
                when {
                    pickupContext && pickupKm == null -> pickupKm = distances.first()
                    rideContext && rideKm == null -> rideKm = distances.last()
                    totalContext && explicitTotalKm == null -> explicitTotalKm = distances.last()
                }
            }
            if (minutes.isNotEmpty()) {
                when {
                    pickupContext && pickupMin == null -> pickupMin = minutes.first()
                    rideContext && rideMin == null -> rideMin = minutes.last()
                }
            }
        }

        // Fallback for the common offer layout: pickup segment appears before trip segment.
        val uniqueDistances = distanceFallback.filterIndexed { idx, v ->
            idx == 0 || kotlin.math.abs(v - distanceFallback[idx - 1]) > 0.01
        }
        if (pickupKm == null && rideKm == null && uniqueDistances.size >= 2) {
            pickupKm = uniqueDistances[0]
            rideKm = uniqueDistances[1]
        } else {
            if (pickupKm == null && uniqueDistances.isNotEmpty()) pickupKm = uniqueDistances.firstOrNull()
            if (rideKm == null && uniqueDistances.size >= 2) rideKm = uniqueDistances.firstOrNull { v -> pickupKm == null || kotlin.math.abs(v - pickupKm!!) > 0.01 }
        }

        // If the screen explicitly exposes only total distance, keep it as the ride distance
        // and mark confidence lower rather than inventing a pickup distance.
        if (rideKm == null && explicitTotalKm != null) rideKm = explicitTotalKm

        val minutes = when {
            pickupMin != null && rideMin != null -> pickupMin!! + rideMin!!
            minuteFallback.size >= 2 -> minuteFallback.take(2).sum()
            minuteFallback.isNotEmpty() -> minuteFallback.first()
            else -> null
        }

        var score = 0
        if (fare != null) score += 35
        if (pickupKm != null) score += 20
        if (rideKm != null) score += 25
        if (minutes != null) score += 20
        score = max(0, score.coerceAtMost(100))

        val ride = if (fare != null && pickupKm != null && rideKm != null && minutes != null) {
            RideInput(fare, pickupKm!!, rideKm!!, minutes)
        } else null

        val missing = buildList {
            if (fare == null) add("valor")
            if (pickupKm == null) add("km até buscar")
            if (rideKm == null) add("km da viagem")
            if (minutes == null) add("tempo")
        }
        val message = if (ride != null) {
            "Oferta lida: R$ %.2f • %.1f km até buscar • %.1f km viagem • %.0f min".format(
                fare, pickupKm, rideKm, minutes
            )
        } else {
            "Não consegui ler tudo. Faltou: ${missing.joinToString(", ")}. Toque em 📸 novamente com a oferta visível."
        }

        return ParsedScreenOffer(ride, fare, pickupKm, rideKm, minutes, score, message, raw)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun number(v: String): Double? = v.replace(',', '.').toDoubleOrNull()
}
