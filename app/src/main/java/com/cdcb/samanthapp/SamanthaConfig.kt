package com.cdcb.samanthapp

import android.content.Context
import android.util.Log
import java.io.File

data class SamanthaConfigData(
    val baseUrl: String = SamanthaConfig.DEFAULT_BASE_URL,
    val model: String = SamanthaConfig.DEFAULT_MODEL,
    val reasoningEffort: String = "high",
    val maxTokens: Int = 800,
    val temperature: Double = 0.6,
    val firecrawlApiKey: String = "",
    val firecrawlMaxResults: Int = 5,
    val firecrawlLang: String = "es",
    val firecrawlCountry: String = "CO",
    val firecrawlTimeoutMs: Int = 15_000,
    val historyLimit: Int = 8,
)

/**
 * Configuración avanzada en `config.yaml`, al estilo del PC.
 *
 * Se copia desde `assets/config.yaml` a
 * `Android/data/com.cdcb.samanthapp/files/config.yaml` en el primer arranque.
 * Editarla y reiniciar la app es suficiente; no hay que recompilar.
 */
object SamanthaConfig {
    const val DEFAULT_BASE_URL = "https://opencode.ai/inference/go/openai/v1"
    const val DEFAULT_MODEL = "claude-haiku-5-5"
    const val FILE_NAME = "config.yaml"
    private const val TAG = "SamanthaConfig"

    fun load(context: Context): SamanthaConfigData {
        val archivo = ensureFile(context)
        val texto = runCatching { archivo.readText() }.getOrDefault("")
        val config = parse(texto)
        Log.i(
            TAG,
            "config=${archivo.absolutePath} model=${config.model} " +
                "reasoning=${config.reasoningEffort} max_tokens=${config.maxTokens} " +
                "temperature=${config.temperature} history=${config.historyLimit} " +
                "firecrawl=${if (config.firecrawlApiKey.isNotBlank()) "sí (${config.firecrawlApiKey.length} chars)" else "no"}"
        )
        return config
    }

    fun ensureFile(context: Context): File {
        val carpeta = context.getExternalFilesDir(null) ?: context.filesDir
        val destino = File(carpeta, FILE_NAME)
        if (!destino.exists()) {
            carpeta.mkdirs()
            runCatching {
                context.assets.open(FILE_NAME).use { entrada ->
                    destino.outputStream().use { salida -> entrada.copyTo(salida) }
                }
            }
        }
        return destino
    }

    /** Parser del subconjunto de YAML que usa la app (`ai:` + `firecrawl:`). */
    fun parse(texto: String): SamanthaConfigData {
        var baseUrl = DEFAULT_BASE_URL
        var model = DEFAULT_MODEL
        var effort = "high"
        var maxTokens = 800
        var temperature = 0.6
        var firecrawlKey = ""
        var firecrawlMax = 5
        var firecrawlLang = "es"
        var firecrawlCountry = "CO"
        var firecrawlTimeout = 15
        var historyLimit = 8
        var seccion = ""

        for (lineaCruda in texto.lineSequence()) {
            val linea = lineaCruda.substringBefore("#").trim()
            if (linea.isEmpty()) continue
            if (linea.endsWith(":")) {
                seccion = linea.dropLast(1).trim().lowercase()
                continue
            }
            if (seccion != "ai" && seccion != "firecrawl" && seccion != "memory") continue
            val corte = linea.indexOf(':')
            if (corte <= 0) continue
            val clave = linea.substring(0, corte).trim().lowercase()
            val valor = linea.substring(corte + 1).trim().trim('"', '\'')
            when (seccion) {
                "ai" -> when (clave) {
                    "base_url" -> baseUrl = valor.ifBlank { baseUrl }
                    "model" -> model = valor.ifBlank { model }
                    "reasoning_effort" -> effort = valor.lowercase()
                    "max_tokens" -> valor.toIntOrNull()
                        ?.takeIf { it in 1..8000 }
                        ?.let { maxTokens = it }
                    "temperature" -> valor.replace(',', '.').toDoubleOrNull()
                        ?.coerceIn(0.0, 2.0)
                        ?.let { temperature = it }
                }
                "firecrawl" -> when (clave) {
                    "api_key" -> firecrawlKey = valor
                    "max_results" -> valor.toIntOrNull()
                        ?.takeIf { it in 1..10 }
                        ?.let { firecrawlMax = it }
                    "lang" -> if (valor.isNotBlank()) firecrawlLang = valor
                    "country" -> if (valor.isNotBlank()) firecrawlCountry = valor
                    "timeout" -> valor.toIntOrNull()
                        ?.takeIf { it in 5..60 }
                        ?.let { firecrawlTimeout = it }
                }
                "memory" -> when (clave) {
                    "recent_window", "history_limit" -> valor.toIntOrNull()
                        ?.takeIf { it in 2..200 }
                        ?.let { historyLimit = it }
                }
            }
        }

        return SamanthaConfigData(
            baseUrl,
            model,
            effort,
            maxTokens,
            temperature,
            firecrawlKey,
            firecrawlMax,
            firecrawlLang,
            firecrawlCountry,
            firecrawlTimeout * 1000,
            historyLimit,
        )
    }
}
