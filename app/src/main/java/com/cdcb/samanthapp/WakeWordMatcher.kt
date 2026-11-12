package com.cdcb.samanthapp

import java.text.Normalizer
import java.util.Locale

/** Reglas puras del detector de «Hola Samantha» (probadas en JVM). */
object WakeWordMatcher {

    /** Normaliza tildes/signos y exige un saludo + el nombre. */
    fun esHolaSamantha(texto: String): Boolean {
        val limpio = Normalizer.normalize(
            texto.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (limpio.isBlank()) return false
        val palabras = limpio.split(' ')
        val saludo = palabras.any {
            it == "hola" || it == "ola" || it == "oye" || it == "hey"
        }
        val nombre = palabras.any {
            similitud(it, "samantha") >= 0.78 || similitud(it, "samanta") >= 0.86
        }
        return saludo && nombre
    }

    /** Similitud 0..1 basada en distancia de Levenshtein. */
    fun similitud(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val fila = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var anterior = fila[0]
            fila[0] = i
            for (j in 1..b.length) {
                val guardado = fila[j]
                fila[j] = minOf(
                    fila[j] + 1,
                    fila[j - 1] + 1,
                    anterior + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                anterior = guardado
            }
        }
        val distancia = fila[b.length]
        return 1.0 - distancia.toDouble() / maxOf(a.length, b.length)
    }
}
