package com.cdcb.samanthapp

import java.text.Normalizer
import java.util.Locale

/**
 * Extrae el nombre del usuario de frases como "me llamo Ana" o "soy Juan".
 *
 * "soy" solo se acepta cuando aún no hay nombre guardado (evita confundir
 * "soy de Bogotá" con un nombre). Devuelve el nombre capitalizado.
 */
object NameExtractor {

    private val explicitas = listOf(
        Regex("(?iu)\\bme\\s+llamo\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bmi\\s+nombre\\s+es\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bll[aá]mame\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bmy\\s+name\\s+is\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bcall\\s+me\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bi'?m\\s+([\\p{L}]{2,24})"),
        Regex("(?iu)\\bthis\\s+is\\s+([\\p{L}]{2,24})"),
    )
    private val soy = Regex("(?iu)\\bsoy\\s+([\\p{L}]{3,24})")

    private val bloqueadas = setOf(
        "de", "del", "la", "el", "un", "una", "unos", "unas", "muy", "tu", "su",
        "mi", "yo", "que", "para", "con", "sin", "por", "en", "y", "o", "a", "al",
        "asi", "así", "aqui", "aquí", "alla", "allá", "este", "esta", "esto",
        "todo", "todos", "nada", "algo", "bueno", "malo", "feliz", "triste",
        "fine", "good", "ok", "okay", "here", "sorry", "not", "just", "from",
        "the", "a", "an", "so", "very", "happy", "sad", "tired", "busy",
        "ready", "sure", "well", "doing", "going", "feeling", "your", "my",
    )

    private val noNombres = setOf(
        "no", "si", "sí", "nada", "gracias", "hola", "bien", "mal", "regular",
        "que", "qué", "como", "cómo", "quien", "quién", "cual", "cuál",
        "porque", "porqué", "tal", "vez", "claro", "ok", "vale", "bueno",
        "mira", "oye", "perdon", "perdón", "disculpa", "ayuda", "espera",
        "yes", "no", "okay", "hello", "hi", "what", "who", "why", "how",
        "fine", "good", "bad", "nothing", "thanks", "maybe", "sure", "well",
    )

    fun extract(text: String, allowSoy: Boolean = true): String? {
        for (patron in explicitas) {
            val match = patron.find(text) ?: continue
            return limpiar(match.groupValues[1]) ?: continue
        }
        if (allowSoy) {
            val match = soy.find(text) ?: return null
            return limpiar(match.groupValues[1])
        }
        return null
    }

    /**
     * Respuesta corta a "¿cómo te llamas?": de 1 a 3 palabras que no sean una
     * frase común ("no sé", "qué", "hola"...). Devuelve el nombre capitalizado.
     */
    fun extractShortAnswer(text: String): String? {
        val crudas = text.trim()
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}]"), "") }
            .filter { it.isNotBlank() }
        if (crudas.size !in 1..3) return null
        if (crudas.any { normalizar(it) in noNombres }) return null
        val nombre = crudas.joinToString(" ")
        if (nombre.length !in 2..40) return null
        return crudas.joinToString(" ") { palabra ->
            palabra.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale("es")) else it.toString()
            }
        }
    }

    private fun palabras(texto: String): List<String> =
        texto.split(Regex("\\s+"))
            .map { normalizar(it) }
            .filter { it.isNotBlank() }

    private fun normalizar(texto: String): String {
        var t = Normalizer.normalize(texto.lowercase(Locale("es")), Normalizer.Form.NFD)
        t = t.replace(Regex("\\p{Mn}+"), "")
        return t.replace(Regex("[^a-zñ]+"), "")
    }

    private fun limpiar(raw: String): String? {
        val nombre = raw.trim().lowercase(Locale("es"))
        if (nombre.length < 2 || nombre in bloqueadas) return null
        return nombre.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("es")) else it.toString()
        }
    }
}

/** Comandos locales de memoria: se ejecutan sin llamar al modelo. */
object MemoryCommands {

    sealed class Result {
        data class Remember(val fact: String) : Result()
        data class Forget(val query: String) : Result()
    }

    private val recuerda = Regex(
        "(?iu)^\\s*(?:recu[eé]rda(?:me(?:lo)?)?|acu[eé]rdate(?:\\s+de)?|" +
            "ten\\s+en\\s+cuenta|no\\s+olvides|anota|" +
            "guarda(?:\\s+en\\s+memoria)?|remember(?:\\s+that)?|" +
            "keep\\s+in\\s+mind(?:\\s+that)?|don'?t\\s+forget(?:\\s+that)?|" +
            "note(?:\\s+that)?|save(?:\\s+that)?)" +
            "\\s+(?:que\\s+|that\\s+)?(.+?)\\s*[.!?]?\\s*$"
    )
    private val olvida = Regex(
        "(?iu)^\\s*(?:olv[ií]da(?:te\\s+de)?|borra\\s+de\\s+tu\\s+memoria|" +
            "quita\\s+de\\s+tu\\s+memoria|forget(?:\\s+about)?(?:\\s+that)?|" +
            "delete(?:\\s+that)?)" +
            "\\s+(?:que\\s+|that\\s+)?(.+?)\\s*[.!?]?\\s*$"
    )

    fun parse(text: String): Result? {
        olvida.find(text)?.let { return Result.Forget(it.groupValues[1]) }
        recuerda.find(text)?.let { return Result.Remember(it.groupValues[1]) }
        return null
    }
}
