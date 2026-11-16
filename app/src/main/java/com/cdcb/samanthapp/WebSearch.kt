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
    private val maxResults: Int = 10,
    private val lang: String = "es",
    private val country: String = "CO",
    private val timeoutMs: Int = 15_000,
    private val tavilyApiKey: String = "",
    private val tavilyTimeoutMs: Int = 20_000,
) {
    fun search(query: String, limit: Int = maxResults): String {
        if (tavilyApiKey.isNotBlank()) return buscarTavily(query, limit)
        return buscarFirecrawl(query, limit)
    }

    private fun buscarTavily(query: String, limit: Int): String {
        val consulta = query.trim()
        if (consulta.isEmpty()) return "Error: la consulta web está vacía."
        if (tavilyApiKey.isBlank()) {
            return "Error: falta tavily.api_key en config.yaml."
        }
        val n = limit.coerceIn(1, 10).coerceAtMost(maxResults)
        val body = JSONObject()
            .put("query", consulta)
            .put("search_depth", "basic")
            .put("max_results", n)
            .put("include_raw_content", "markdown")
            .put("include_usage", true)
            .put("topic", "general")
            .put("language", if (lang.isBlank()) "es" else lang)

        val conn = (URL(TAVILY_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = tavilyTimeoutMs
            readTimeout = tavilyTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $tavilyApiKey")
            setRequestProperty("User-Agent", "samanthapp-android/1.0")
        }
        try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val detalle = payload.optJSONObject("detail")
                    ?.optString("error")
                    .orEmpty()
                    .take(160)
                return when (code) {
                    401 -> "Error: Tavily rechazó la API key (401). " +
                        "Revisa tavily.api_key en config.yaml."
                    429 -> "Error: límite de Tavily alcanzado (429)."
                    432, 433 -> "Error: límite del plan de Tavily alcanzado ($code)."
                    434 -> "Error: Tavily no pudo leer contenido en vivo; " +
                        "intenta de nuevo en un momento."
                    else -> "Error: Tavily respondió HTTP $code" +
                        if (detalle.isNotBlank()) ": $detalle" else "."
                }
            }

            val results = payload.optJSONArray("results")
                ?: return "No encontré resultados web para esa consulta."
            if (results.length() == 0) {
                return "No encontré resultados web para esa consulta."
            }

            data class Resultado(
                val title: String,
                val url: String,
                val resumen: String,
                val raw: String,
            )
            val items = mutableListOf<Resultado>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = textoDe(item, "title").trim().take(120)
                val url = textoDe(item, "url").trim()
                val resumen = textoDe(item, "content")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(600)
                val raw = textoDe(item, "raw_content").trim()
                items.add(Resultado(title.ifBlank { "Sin título" }, url, resumen, raw))
                if (items.size >= n) break
            }
            if (items.isEmpty()) {
                return "No encontré resultados web para esa consulta."
            }

            // Tavily ya trae raw_content; solo se usa Jsoup si falta en algún
            // resultado y la página es accesible desde el teléfono.
            val faltantes = items.filter {
                it.raw.isBlank() && it.url.startsWith("https://", true)
            }.map { it.url }
            val extraidos = if (faltantes.isEmpty()) {
                emptyMap()
            } else {
                HtmlExtractor.extraerVarias(faltantes)
            }

            val salida = StringBuilder()
            var restante = HtmlExtractor.MAX_TOTAL_CHARS
            var conContenido = 0
            items.forEachIndexed { indice, item ->
                salida.append("${indice + 1}. ${item.title}\n")
                val resumen = item.resumen.take(minOf(600, restante)).trim()
                if (resumen.isNotBlank()) {
                    salida.append("   $resumen\n")
                    restante -= resumen.length
                }
                if (item.url.isNotBlank()) salida.append("   ${item.url}\n")
                val contenido = item.raw.ifBlank { extraidos[item.url].orEmpty() }
                if (contenido.isNotBlank() && restante > 0) {
                    val recorte = contenido
                        .take(minOf(HtmlExtractor.MAX_PAGE_CHARS, restante))
                        .trim()
                    val conSangria = recorte.lineSequence().joinToString("\n   ") { it }
                    salida.append("   Contenido: $conSangria\n")
                    restante -= recorte.length
                    conContenido++
                } else if (contenido.isNotBlank()) {
                    salida.append("   Contenido: omitido por límite de espacio.\n")
                } else {
                    salida.append("   Contenido: no disponible (la página no se pudo leer).\n")
                }
            }
            val texto = salida.toString().trim()
            val creditos = payload.optJSONObject("usage")
                ?.optInt("credits", 0) ?: 0
            Log.i(
                TAG,
                "tavily: ${items.size} resultados, $conContenido con contenido, " +
                    "creditos=$creditos, ${texto.length} chars",
            )
            return texto.ifBlank { "No encontré resultados web para esa consulta." }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "tavily timeout")
            return "Error: la búsqueda tardó demasiado. Intenta de nuevo."
        } catch (e: Exception) {
            Log.w(TAG, "tavily: ${e.javaClass.simpleName}")
            return "Error: no pude conectar con Tavily."
        } finally {
            conn.disconnect()
        }
    }

    private fun textoDe(item: JSONObject, clave: String): String {
        return when (val valor = item.opt(clave)) {
            null, JSONObject.NULL -> ""
            is String -> valor
            else -> valor.toString()
        }
    }

    private fun buscarFirecrawl(query: String, limit: Int): String {
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

            data class Resultado(val title: String, val url: String, val desc: String)
            val resultados = mutableListOf<Resultado>()
            for (i in 0 until web.length()) {
                val item = web.optJSONObject(i) ?: continue
                val title = item.optString("title", "Sin título").trim().take(120)
                val url = item.optString("url").trim()
                val desc = item.optString("description")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(300)
                resultados.add(Resultado(title, url, desc))
                if (resultados.size >= n) break
            }
            if (resultados.isEmpty()) {
                return "No encontré resultados web para esa consulta."
            }

            // Se lee cada enlace en el teléfono con Jsoup: sin scrape de
            // Firecrawl y sin gastar créditos extra.
            val contenidos = HtmlExtractor.extraerVarias(
                resultados.map { it.url }.filter { it.startsWith("https://", true) }
            )

            val salida = StringBuilder()
            var restante = HtmlExtractor.MAX_TOTAL_CHARS
            var conContenido = 0
            resultados.forEachIndexed { indice, item ->
                salida.append("${indice + 1}. ${item.title}\n")
                if (item.desc.isNotBlank()) salida.append("   ${item.desc}\n")
                if (item.url.isNotBlank()) salida.append("   ${item.url}\n")
                val texto = contenidos[item.url]?.trim()
                if (!texto.isNullOrEmpty() && restante > 0) {
                    val recorte = texto.take(restante).trim()
                    val conSangria = recorte.lineSequence()
                        .joinToString("\n   ") { it }
                    salida.append("   Contenido: $conSangria\n")
                    restante -= recorte.length
                    conContenido++
                } else {
                    salida.append("   Contenido: no disponible (la página no se pudo leer).\n")
                }
            }
            val texto = salida.toString().trim()
            Log.i(
                TAG,
                "firecrawl: ${resultados.size} resultados, $conContenido con Jsoup, " +
                    "${texto.length} chars",
            )
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
        private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
    }
}
