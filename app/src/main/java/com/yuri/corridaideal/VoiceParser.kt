package com.yuri.corridaideal

import java.text.Normalizer

sealed class VoiceCommand {
    data class Ride(val fare: Double, val pickupKm: Double, val rideKm: Double, val minutes: Double) : VoiceCommand()
    data class CurrentConsumption(val kml: Double) : VoiceCommand()
    data class SaveBaseConsumption(val kml: Double) : VoiceCommand()
    data class SpeedLimit(val kmh: Double) : VoiceCommand()
    data object Status : VoiceCommand()
    data object Unknown : VoiceCommand()
}

object VoiceParser {
    fun parse(raw: String): VoiceCommand {
        val text = normalize(raw)
        val numbers = extractNumbers(text)

        if ((text.contains("como esta") || text.contains("status") || text.contains("vale a pena"))) {
            return VoiceCommand.Status
        }

        if ((text.contains("salvar media") || text.contains("media base")) && numbers.isNotEmpty()) {
            return VoiceCommand.SaveBaseConsumption(numbers.first())
        }

        if ((text.contains("consumo") || text.contains("por litro") || text.contains("quilometro por litro")) && numbers.isNotEmpty()) {
            return VoiceCommand.CurrentConsumption(numbers.first())
        }

        if ((text.contains("limite") || text.contains("velocidade maxima")) && numbers.isNotEmpty()) {
            return VoiceCommand.SpeedLimit(numbers.first())
        }

        if ((text.contains("corrida") || text.contains("oferta") || text.contains("valor")) && numbers.size >= 4) {
            return VoiceCommand.Ride(numbers[0], numbers[1], numbers[2], numbers[3])
        }

        return VoiceCommand.Unknown
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .trim()
    }

    private fun extractNumbers(text: String): List<Double> {
        // Android speech recognition commonly returns spoken numbers as digits.
        val digitMatches = Regex("\\d+(?:[\\.,]\\d+)?").findAll(text)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .toList()
        if (digitMatches.isNotEmpty()) return digitMatches

        // Fallback for common Portuguese number words used in ride values.
        return spokenNumberChunks(text).mapNotNull { portugueseWordsToNumber(it) }
    }

    private fun spokenNumberChunks(text: String): List<String> {
        val allowed = setOf(
            "zero","um","uma","dois","duas","tres","quatro","cinco","seis","sete","oito","nove",
            "dez","onze","doze","treze","quatorze","catorze","quinze","dezesseis","dezessete","dezoito","dezenove",
            "vinte","trinta","quarenta","cinquenta","sessenta","setenta","oitenta","noventa","cem","cento","e","virgula"
        )
        val chunks = mutableListOf<String>()
        var current = mutableListOf<String>()
        text.split(Regex("\\s+")).forEach { token ->
            if (token in allowed) {
                current.add(token)
            } else if (current.isNotEmpty()) {
                chunks.add(current.joinToString(" "))
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) chunks.add(current.joinToString(" "))
        return chunks
    }

    private fun portugueseWordsToNumber(chunk: String): Double? {
        val parts = chunk.split("virgula", limit = 2)
        val integer = wordsToInt(parts[0].trim()) ?: return null
        if (parts.size == 1) return integer.toDouble()
        val decimalWords = parts[1].trim()
        val decimal = wordsToInt(decimalWords) ?: return integer.toDouble()
        val digits = decimal.toString().length
        return integer + decimal / Math.pow(10.0, digits.toDouble())
    }

    private fun wordsToInt(words: String): Int? {
        val units = mapOf(
            "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3,
            "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
            "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14,
            "catorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17,
            "dezoito" to 18, "dezenove" to 19, "vinte" to 20, "trinta" to 30,
            "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "setenta" to 70,
            "oitenta" to 80, "noventa" to 90, "cem" to 100, "cento" to 100
        )
        var total = 0
        var found = false
        words.split(Regex("\\s+")).forEach { w ->
            if (w == "e" || w.isBlank()) return@forEach
            val v = units[w] ?: return null
            total += v
            found = true
        }
        return if (found) total else null
    }
}
