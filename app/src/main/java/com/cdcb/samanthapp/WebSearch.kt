package com.cdcb.samanthapp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Búsqueda web con Firecrawl v2. La API key viene de `config.yaml`
 * (`firecrawl.api_key`), no de la pantalla de Ajustes.
 */
class WebSearch(
    private val apiKey: String,
    private val maxResults: Int = 5,
    private val lang: String = "es",
    private val country: String = "CO",
    private val timeoutMs: Int = 15_000,
) {
    fun search(query: String, limit: Int = maxResults): String {
        val consulta = query.trim()
        if (consulta.isEmpty()) return "Error: la consulta web está vacía."
        if (apiKey.isBlank()) {
            return "Error: falta firecrawl.api_key en config.yaml."
        }
        val n = limit.coerceIn(1, 10).coerceAtMost(maxResults)
        val body = JSONObject()
            .put("query", consulta)
            .put("limit", n)
            .put("sources", JSONArray().put("web"))
            .put("lang", lang)
            .put("country", country)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "samanthapp-android/1.0")
        }
        try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 401) {
                return "Error: Firecrawl rechazó la API key (401). " +
                    "Revisa firecrawl.api_key en config.yaml."
            }
            if (code == 402) return "Error: Firecrawl se quedó sin créditos (402)."
            if (code == 429) return "Error: límite de Firecrawl alcanzado (429)."
            if (code !in 200..299) {
                return "Error: Firecrawl respondió HTTP $code."
            }

            val payload = JSONObject(raw)
            if (!payload.optBoolean("success", false)) {
                val error = payload.optString("error").lowercase()
                if ("credit" in error || "payment" in error || "quota" in error) {
                    return "Error: Firecrawl se quedó sin créditos."
                }
                return "Error: Firecrawl no pudo completar la búsqueda."
            }
            val data = payload.opt("data")
            val web = when (data) {
                is JSONObject -> data.optJSONArray("web") ?: data.optJSONArray("results")
                is JSONArray -> data
                else -> null
            }
            if (web == null || web.length() == 0) {
                return "No encontré resultados web para esa consulta."
            }

            val salida = StringBuilder()
            var incluidos = 0
            for (i in 0 until web.length()) {
                val item = web.optJSONObject(i) ?: continue
                val title = item.optString("title", "Sin título").trim().take(120)
                val url = item.optString("url").trim()
                val desc = item.optString("description")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(300)
                salida.append("${incluidos + 1}. $title\n")
                if (desc.isNotBlank()) salida.append("   $desc\n")
                if (url.isNotBlank()) salida.append("   $url\n")
                incluidos++
                if (incluidos >= n) break
            }
            val texto = salida.toString().trim()
            Log.i(TAG, "firecrawl: ${consulta.length} chars -> ${texto.length} chars")
            return texto.ifBlank { "No encontré resultados web para esa consulta." }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "firecrawl timeout")
            return "Error: la búsqueda tardó demasiado. Intenta de nuevo."
        } catch (e: Exception) {
            Log.w(TAG, "firecrawl: ${e.javaClass.simpleName}")
            return "Error: no pude conectar con Firecrawl."
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SamanthaSearch"
        private const val ENDPOINT = "https://api.firecrawl.dev/v2/search"
    }
}
