package com.cdcb.samanthapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un turno de la conversación en memoria. */
data class Message(val role: String, val content: String)

/**
 * Cliente mínimo del endpoint OpenAI-compatible de OpenCode Go.
 *
 * No usa librerías HTTP externas: HttpURLConnection + org.json bastan y
 * mantienen el APK pequeño. Se ejecuta fuera del hilo principal.
 */
class SamanthaApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val sessionId: String,
    private val persona: String,
    private val reasoningEffort: String = "high",
    private val maxTokens: Int = 800,
    private val temperature: Double = 0.6,
    private val firecrawlApiKey: String = "",
    private val firecrawlMaxResults: Int = 5,
    private val firecrawlLang: String = "es",
    private val firecrawlCountry: String = "CO",
    private val firecrawlTimeoutMs: Int = 15_000,
    private val historyLimit: Int = 8,
) {

    class ApiException(message: String, val code: Int) : Exception(message)

    fun chat(
        history: List<Message>,
        userText: String,
        userName: String = "",
        facts: List<String> = emptyList(),
        askName: Boolean = false,
        languageMode: String = "es",
    ): String {
        val effort = reasoningEffort.trim().ifEmpty { null }
        if (firecrawlApiKey.isNotBlank()) {
            return try {
                chatWithTools(history, userText, effort, userName, facts, askName, languageMode)
            } catch (e: ApiException) {
                when {
                    e.code == 400 && e.message?.contains("reasoning", true) == true ->
                        chatWithTools(history, userText, null, userName, facts, askName, languageMode)
                    e.code == 400 && (
                        e.message?.contains("tool", true) == true ||
                            e.message?.contains("function", true) == true
                        ) -> request(history, userText, effort, userName, facts, askName, languageMode)
                    else -> throw e
                }
            }
        }
        return try {
            request(history, userText, effort, userName, facts, askName, languageMode)
        } catch (e: ApiException) {
            // Algunos modelos no aceptan reasoning_effort: reintenta sin él.
            if (e.code == 400 && e.message?.contains("reasoning", true) == true) {
                request(history, userText, null, userName, facts, askName, languageMode)
            } else {
                throw e
            }
        }
    }

    /** Turno con tool calling: el modelo puede pedir web_search y reintentar. */
    private fun chatWithTools(
        history: List<Message>,
        userText: String,
        reasoningEffort: String?,
        userName: String,
        facts: List<String>,
        askName: Boolean,
        languageMode: String,
    ): String {
        val convo = buildMessages(history, userText, userName, facts, askName, languageMode)
        val tools = toolsJson()
        val limiteTokens = maxOf(maxTokens, TOOL_MAX_TOKENS)
        var research = 0
        var ultimoTexto = ""
        for (iteracion in 0 until MAX_TOOL_ITERATIONS) {
            val response = postChat(convo, tools, reasoningEffort, limiteTokens)
            val message = response.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: return ultimoTexto.ifBlank { TOOL_LOOP_FALLBACK }
            val content = message.optString("content")
            if (content.isNotBlank()) ultimoTexto = content
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return cleanForSpeech(content).trim()
                    .ifBlank { cleanForSpeech(ultimoTexto).trim() }
                    .ifBlank { TOOL_LOOP_FALLBACK }
            }

            convo.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", content)
                    .put("tool_calls", calls)
            )
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val id = call.optString("id")
                val funcion = call.optJSONObject("function")
                val nombre = funcion?.optString("name").orEmpty()
                val argumentos = funcion?.optString("arguments").orEmpty()
                val args = runCatching {
                    JSONObject(argumentos.ifBlank { "{}" })
                }.getOrElse { JSONObject() }
                val resultado = if (nombre == "web_search") {
                    research++
                    val query = args.optString("query")
                    val limit = args.optInt("limit", firecrawlMaxResults)
                    val busqueda = WebSearch(
                        firecrawlApiKey,
                        firecrawlMaxResults,
                        firecrawlLang,
                        firecrawlCountry,
                        firecrawlTimeoutMs,
                    ).search(query, limit)
                    Log.i("Samantha", "web_search: ${busqueda.length} chars")
                    busqueda
                } else {
                    "Error: herramienta no disponible: $nombre"
                }
                convo.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", resultado)
                )
            }

            if (research >= MAX_RESEARCH_CALLS) {
                return contenidoDe(postChat(convo, null, reasoningEffort, limiteTokens))
                    .ifBlank { TOOL_LOOP_FALLBACK }
            }
        }
        return contenidoDe(postChat(convo, null, reasoningEffort, limiteTokens))
            .ifBlank { TOOL_LOOP_FALLBACK }
    }

    private fun buildMessages(
        history: List<Message>,
        userText: String,
        userName: String,
        facts: List<String>,
        askName: Boolean,
        languageMode: String,
    ): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt(userName, facts, askName, languageMode))
        )
        for (turno in history.takeLast(historyLimit)) {
            messages.put(JSONObject().put("role", turno.role).put("content", turno.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        return messages
    }

    private fun postChat(
        messages: JSONArray,
        tools: JSONArray?,
        reasoningEffort: String?,
        maxTokensOverride: Int = maxTokens,
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokensOverride)
            .put("temperature", temperature)
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort)
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }

        val conn = (URL("${baseUrl.trimEnd('/')}/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "samanthapp-android/1.0")
            setRequestProperty("x-opencode-session", sessionId)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw ApiException("HTTP $code: ${extractError(raw)}", code)
            }
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun contenidoDe(response: JSONObject): String {
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        return cleanForSpeech(content).trim()
    }

    private fun toolsJson(): JSONArray {
        val parametros = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "query",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Consulta web corta en lenguaje natural.")
                    )
                    .put(
                        "limit",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", 1)
                            .put("maximum", 10)
                            .put("description", "Número de resultados (1-10).")
                    )
            )
            .put("required", JSONArray().put("query"))
        val funcion = JSONObject()
            .put("name", "web_search")
            .put(
                "description",
                "Busca en la web información actual o verificable: noticias, " +
                    "precios, eventos, personas o datos recientes. Devuelve título, " +
                    "resumen y URL. Usa una consulta corta y natural; no la uses " +
                    "para definiciones que ya conoces."
            )
            .put("parameters", parametros)
        return JSONArray().put(JSONObject().put("type", "function").put("function", funcion))
    }

    private fun request(
        history: List<Message>,
        userText: String,
        reasoningEffort: String?,
        userName: String,
        facts: List<String>,
        askName: Boolean,
        languageMode: String,
    ): String {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt(userName, facts, askName, languageMode))
        )
        for (turno in history.takeLast(historyLimit)) {
            messages.put(JSONObject().put("role", turno.role).put("content", turno.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort)

        val conn = (URL("${baseUrl.trimEnd('/')}/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "samanthapp-android/1.0")
            setRequestProperty("x-opencode-session", sessionId)
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw ApiException("HTTP $code: ${extractError(raw)}", code)
            }

            val content = JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            return cleanForSpeech(content).trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun extractError(raw: String): String = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")
            ?: raw.take(160)
    } catch (_: Exception) {
        raw.take(160)
    }

    private fun systemPrompt(
        userName: String,
        facts: List<String>,
        askName: Boolean,
        languageMode: String,
    ): String {
        val fecha = SimpleDateFormat(
            if (languageMode == "en") "EEEE, MMMM d, yyyy, HH:mm"
            else "EEEE d 'de' MMMM 'de' yyyy, HH:mm",
            if (languageMode == "en") Locale.ENGLISH else Locale("es", "CO")
        ).format(Date())
        val base = composeSystemPrompt(persona, userName, facts, fecha, askName, languageMode)
        if (firecrawlApiKey.isBlank()) return base
        return base +
            "\n\n## Búsqueda web\n" +
            "Tienes la herramienta web_search para noticias, precios, eventos, " +
            "personas o datos actuales que no puedas responder con certeza. " +
            "Úsala cuando haga falta; no para definiciones que ya conoces. " +
            "Si la búsqueda falla o no hay resultados, dilo con naturalidad y " +
            "no inventes. No leas URLs en voz alta."
    }

    companion object {
        private const val PERSONA_ASSET = "persona_samantha.md"
        private const val MAX_TOOL_ITERATIONS = 4
        private const val MAX_RESEARCH_CALLS = 2
        private const val TOOL_MAX_TOKENS = 3000
        private const val TOOL_LOOP_FALLBACK =
            "No encontré una respuesta concluyente con lo que pude consultar. " +
                "¿Me lo preguntas de otra forma?"

        @Volatile
        private var cachedPersona: String? = null

        /** Carga `assets/persona_samantha.md` una sola vez (con fallback). */
        fun loadPersona(context: Context): String {
            cachedPersona?.let { return it }
            val leido = try {
                context.assets.open(PERSONA_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } catch (_: Exception) {
                "Eres Samantha, una inteligencia artificial personal que vive en " +
                    "este teléfono. Responde en español, breve y natural, sin " +
                    "markdown, enlaces ni emojis: tu respuesta se escucha."
            }
            cachedPersona = leido
            return leido
        }

        /**
         * Persona + fecha + nombre + recuerdos, en ese orden. Función pura
         * para poder probarla sin Android.
         */
        fun composeSystemPrompt(
            persona: String,
            userName: String,
            facts: List<String>,
            fecha: String,
            askName: Boolean = false,
            languageMode: String = "es",
        ): String {
            val identidad = when {
                userName.isNotBlank() ->
                    "El usuario se llama $userName. Llámalo por su nombre cuando sea natural."
                askName ->
                    "Todavía no sabes el nombre del usuario. Empieza tu respuesta " +
                        "preguntándole cómo se llama, una sola vez y con naturalidad; " +
                        "después salúdalo o atiende lo que te haya pedido. No inventes " +
                        "un nombre."
                else ->
                    "No sabes el nombre del usuario. No lo inventes; si te lo dice, " +
                        "úsalo y no vuelvas a preguntarlo."
            }
            val memoria = if (facts.isEmpty()) {
                "No tienes recuerdos guardados del usuario."
            } else {
                facts.joinToString("\n") { "- $it" }
            }
            return buildString {
                append(persona.trim())
                append("\n\n## Fecha y hora actuales\n")
                append(
                    "Hoy es $fecha (zona de Bogotá). Úsalo para \"hoy\", " +
                        "\"ayer\" o \"mañana\"; no lo busques.\n"
                )
                append(
                    "Este bloque es la única referencia para la fecha y hora de " +
                        "ESTE turno. Los valores de turnos anteriores eran correctos " +
                        "cuando se dijeron; no los trates como errores ni te disculpes " +
                        "por ellos. Si la hora cambió, usa la nueva sin anunciar la " +
                        "corrección ni repetir disculpas.\n"
                )
                append("\n## La persona\n")
                append(identidad)
                append("\n\n## Recuerdos guardados\n")
                append(memoria)
                append("\n\n## Idioma de esta conversación\n")
                val idiomaDefecto = if (languageMode == "en") "inglés" else "español"
                append(
                    "Responde en el mismo idioma del último mensaje de la persona: " +
                        "si te habla en inglés, responde en inglés natural; si te habla " +
                        "en español, responde en español. No mezcles idiomas ni traduzcas " +
                        "sin que te lo pidan. Si el mensaje es ambiguo, usa $idiomaDefecto."
                )
            }
        }

        /** Deja el texto listo para TTS: sin markdown ni etiquetas de razonamiento. */
        fun cleanForSpeech(text: String): String {
            var t = text
            t = Regex("(?is)```.*?```").replace(t, " ")
            t = Regex("(?is)<think(?:ing)?>.*?</think(?:ing)?>").replace(t, " ")
            t = Regex("(?i)</?think(?:ing)?>").replace(t, " ")
            t = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(t, "$1")
            t = Regex("https?://\\S+").replace(t, " ")
            t = Regex("[*_#>|`]+").replace(t, "")
            t = t.replace("…", ".")
            return t.replace(Regex("\\s+"), " ").trim()
        }
    }
}
