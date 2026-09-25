package com.yuri.corridaideal

import java.text.Normalizer

enum class UberScreenState { OFFER, TO_PICKUP, ON_TRIP, COMPLETED, UNKNOWN }

object ScreenStateDetector {
    fun detect(raw: String): UberScreenState {
        val t = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace(Regex("\\s+"), " ")

        if (containsAny(t, "avaliar passageiro", "corrida concluida", "viagem concluida", "ganhos da viagem", "quanto voce ganhou", "avaliacao do passageiro")) {
            return UberScreenState.COMPLETED
        }
        if (containsAny(t, "finalizar viagem", "encerrar viagem", "deslize para finalizar", "a caminho do destino", "destino do passageiro")) {
            return UberScreenState.ON_TRIP
        }
        if (containsAny(t, "iniciar viagem", "deslize para iniciar", "buscar passageiro", "a caminho do passageiro", "embarque", "cheguei", "estou no local")) {
            return UberScreenState.TO_PICKUP
        }
        if ((t.contains("r$") || t.contains("rs ")) && t.contains("km") && containsAny(t, "aceitar", "oferta", "corrida", "viagem")) {
            return UberScreenState.OFFER
        }
        return UberScreenState.UNKNOWN
    }

    private fun containsAny(text: String, vararg terms: String) = terms.any { text.contains(it) }
}
