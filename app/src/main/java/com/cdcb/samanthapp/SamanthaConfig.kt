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
                "temperature=${config.temperature}"
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

    /** Parser del subconjunto de YAML que usa la app (`ai:` + claves simples). */
    fun parse(texto: String): SamanthaConfigData {
        var baseUrl = DEFAULT_BASE_URL
        var model = DEFAULT_MODEL
        var effort = "high"
        var maxTokens = 800
        var temperature = 0.6
        var seccion = ""

        for (lineaCruda in texto.lineSequence()) {
            val linea = lineaCruda.substringBefore("#").trim()
            if (linea.isEmpty()) continue
            if (linea.endsWith(":")) {
                seccion = linea.dropLast(1).trim().lowercase()
                continue
            }
            if (seccion.isNotEmpty() && seccion != "ai") continue
            val corte = linea.indexOf(':')
            if (corte <= 0) continue
            val clave = linea.substring(0, corte).trim().lowercase()
            val valor = linea.substring(corte + 1).trim().trim('"', '\'')
            when (clave) {
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
        }

        return SamanthaConfigData(baseUrl, model, effort, maxTokens, temperature)
    }
}
