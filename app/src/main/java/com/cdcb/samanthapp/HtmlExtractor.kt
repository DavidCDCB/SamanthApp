package com.cdcb.samanthapp

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lector local de páginas para complementar los resultados de Firecrawl sin
 * gastar créditos de scrape. Descarga solo HTTPS y extrae el texto principal
 * con Jsoup. Las páginas con JavaScript, anti-bot o PDF quedan sin contenido;
 * en ese caso se conserva el resumen que ya devolvió Firecrawl.
 */
object HtmlExtractor {

    const val MAX_PAGE_CHARS = 1_500
    const val MAX_TOTAL_CHARS = 15_000

    private const val TAG = "SamanthaHtml"
    private const val PAGE_TIMEOUT_MS = 8_000
    private const val TOTAL_TIMEOUT_MS = 12_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_WORKERS = 5
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /**
     * Descarga y extrae varias páginas en paralelo. Devuelve el texto (o null)
     * por URL; no lanza si una página falla.
     */
    fun extraerVarias(urls: List<String>): Map<String, String?> {
        val limpias = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (limpias.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(MAX_WORKERS, limpias.size))
        return try {
            val tareas = limpias.map { url ->
                Callable { url to extraer(url) }
            }
            val futuros = pool.invokeAll(
                tareas,
                TOTAL_TIMEOUT_MS.toLong(),
                TimeUnit.MILLISECONDS,
            )
            futuros.mapIndexed { indice, futuro ->
                val valor = if (futuro.isCancelled || !futuro.isDone) {
                    null
                } else {
                    runCatching { futuro.get().second }.getOrNull()
                }
                limpias[indice] to valor
            }.toMap()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Descarga una página (solo https) y devuelve su texto principal. */
    fun extraer(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (!hostPublico(host)) return null
        return try {
            val respuesta = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(PAGE_TIMEOUT_MS)
                .maxBodySize(MAX_BODY_BYTES)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .execute()
            val urlFinal = respuesta.url()?.toString()
            if (urlFinal != null) {
                val hostFinal = runCatching { URI(urlFinal).host }.getOrNull()
                if (hostFinal == null || !hostPublico(hostFinal)) return null
            }
            if (respuesta.statusCode() !in 200..299) return null
            extraerTexto(respuesta.parse())
        } catch (e: Exception) {
            Log.d(TAG, "no se pudo leer $url: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Extracción pura (sin red), útil para pruebas. */
    fun extraerTexto(html: String, baseUrl: String = ""): String =
        extraerTexto(Jsoup.parse(html, baseUrl))

    private fun extraerTexto(doc: Document): String {
        doc.select(
            "script,style,noscript,template,svg,iframe,canvas,form,nav,header,footer,aside," +
                "[class*=cookie],[class*=consent],[id*=cookie],[id*=consent]"
        ).remove()
        val principal = doc.selectFirst("article, main, [role=main]") ?: doc.body() ?: return ""
        val bloques = principal.select("h1,h2,h3,h4,p,li,blockquote,pre,td")
        var texto = if (bloques.isNotEmpty()) {
            bloques.joinToString("\n") { it.text().trim() }.trim()
        } else {
            principal.text()
        }
        // Páginas hechas con divs: si los bloques no alcanzan, usar todo el
        // texto visible del contenedor principal.
        if (texto.length < 200) texto = principal.text()
        texto = texto
            .replace(Regex("[\\t\\u000B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (texto.length > MAX_PAGE_CHARS) {
            texto = texto.take(MAX_PAGE_CHARS).trimEnd() + "…"
        }
        return texto
    }

    /** Evita descargar localhost o direcciones privadas desde un enlace. */
    private fun hostPublico(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return false
        return try {
            InetAddress.getAllByName(host).all { direccion ->
                !direccion.isAnyLocalAddress &&
                    !direccion.isLoopbackAddress &&
                    !direccion.isLinkLocalAddress &&
                    !direccion.isSiteLocalAddress &&
                    !direccion.isMulticastAddress
            }
        } catch (_: Exception) {
            false
        }
    }
}