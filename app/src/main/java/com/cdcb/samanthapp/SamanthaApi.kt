package com.cdcb.samanthapp

import android.content.Context
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
        return try {
            request(history, userText, effort, userName, facts, askName, languageMode)
        } catch (e: ApiException) {
            // Algunos modelos no aceptan reasoning_effort: reintenta sin él.
            if (e.code == 400 && e.message?.contains("reasoning", ignoreCase = true) == true) {
                request(history, userText, null, userName, facts, askName, languageMode)
            } else {
                throw e
            }
        }
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
        for (turno in history.takeLast(8)) {
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
        return composeSystemPrompt(persona, userName, facts, fecha, askName, languageMode)
    }

    companion object {
        private const val PERSONA_ASSET = "persona_samantha.md"

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
