package com.cdcb.samanthapp

import android.content.SharedPreferences
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Motor TTS, selección de voz, velocidad y revelado del texto al ritmo de la
 * voz. Mantiene la tarjeta de respuesta sincronizada con lo que se escucha.
 */
class TtsController(
    private val activity: MainActivity,
    private val prefs: SharedPreferences,
    private val response: TextView,
    private val responseScroll: NestedScrollView,
    private val languageMode: () -> String,
    private val onSpeaking: () -> Unit,
    private val onFinished: () -> Unit,
    private val onNotReady: () -> Unit,
    private val onResponseShown: () -> Unit,
    private val onPreviewStarting: () -> Unit,
) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var previewTts: TextToSpeech? = null
    private var pendingSpeech: String? = null

    var speechRate = prefs.getFloat(KEY_TTS_RATE, 1.05f).coerceIn(0.5f, 2.0f)
        private set

    private var ultimaUtterance = ""
    private var respuestaActual = ""
    private var respuestaColor = Color.WHITE
    private var reveladoHasta = 0
    private var reveladoConVoz = false
    private var reveladoFallbackActivo = false
    private var reveladoFallbackIndice = 0
    private var reveladoPalabras: List<IntRange> = emptyList()

    private val reveladoFallbackRunnable = object : Runnable {
        override fun run() {
            if (!reveladoConVoz || !reveladoFallbackActivo) return
            if (reveladoFallbackIndice >= reveladoPalabras.size) {
                revealAll()
                return
            }
            val rango = reveladoPalabras[reveladoFallbackIndice++]
            applyReveal(rango.first, rango.last + 1)
            response.postDelayed(this, revealIntervalMs())
        }
    }

    init {
        initTts()
    }

    // ── motor ─────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(activity) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            val motor = tts ?: return@TextToSpeech
            val locale = if (languageMode() == "en") {
                Locale.US
            } else {
                Locale("es", "CO")
            }
            var resultado = motor.setLanguage(locale)
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                resultado = motor.setLanguage(
                    if (languageMode() == "en") Locale.ENGLISH else Locale("es", "ES")
                )
            }
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                if (languageMode() != "en") resultado = motor.setLanguage(Locale("es"))
            }
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady = false
                return@TextToSpeech
            }
            val voz = storedVoice(motor) ?: bestVoice(motor.voices, languageMode())
            if (voz != null) {
                motor.voice = voz
                Log.i(
                    "Samantha",
                    "voz TTS: ${voz.name} | ${voz.locale} | " +
                        "quality=${voz.quality} | red=${voz.isNetworkConnectionRequired}"
                )
            }
            motor.setSpeechRate(speechRate)
            motor.setPitch(1.0f)
            motor.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int,
                ) {
                    if (utteranceId == null || utteranceId == ultimaUtterance) {
                        activity.runOnUiThread { revealUntil(start, end) }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == ultimaUtterance) {
                        activity.runOnUiThread { finishSpeaking() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == ultimaUtterance) {
                        activity.runOnUiThread { finishSpeaking() }
                    }
                }
            })
            pendingSpeech?.let { pendiente ->
                pendingSpeech = null
                activity.runOnUiThread { speak(pendiente) }
            }
        }
    }

    private fun storedVoice(motor: TextToSpeech): Voice? {
        val nombre = prefs.getString(KEY_TTS_VOICE, "").orEmpty()
        if (nombre.isBlank()) return null
        return motor.voices?.firstOrNull {
            it.name == nombre && it.locale.language == languageMode()
        }
    }

    private fun bestVoice(voces: Set<Voice>?, idioma: String): Voice? {
        val candidatas = voces?.filter { it.locale.language == idioma } ?: return null
        if (candidatas.isEmpty()) return null
        return candidatas.maxByOrNull { voz ->
            var puntos = voz.quality
            if (idioma == "es") {
                when {
                    voz.locale.country.equals("CO", true) -> puntos += 60
                    voz.locale.country.equals("US", true) -> puntos += 40
                    voz.locale.country.equals("MX", true) -> puntos += 30
                }
            }
            if (voz.isNetworkConnectionRequired) puntos += 20
            puntos
        }
    }

    fun chooseVoice(idioma: String = languageMode()) {
        val motor = tts ?: return
        val voces = motor.voices
            ?.filter { it.locale.language == idioma }
            ?.sortedWith(compareBy({ it.locale.country }, { it.name }))
            ?: emptyList()
        if (voces.isEmpty()) {
            return
        }

        val etiquetas = mutableListOf(activity.getString(R.string.settings_voice_auto))
        val nombres = mutableListOf("")
        for (voz in voces) {
            val red = if (voz.isNetworkConnectionRequired) " · online" else " · local"
            etiquetas += "${voz.name} · ${voz.locale}$red"
            nombres += voz.name
        }

        val actual = if (idioma == languageMode()) {
            prefs.getString(KEY_TTS_VOICE, "").orEmpty()
        } else {
            ""
        }
        val marcada = nombres.indexOf(actual).coerceAtLeast(0)
        val etiquetaIdioma = if (idioma == "en") "English" else "Español"

        MaterialAlertDialogBuilder(activity)
            .setTitle("${activity.getString(R.string.settings_voice)} · $etiquetaIdioma")
            .setSingleChoiceItems(etiquetas.toTypedArray(), marcada) { _, which ->
                val nombre = nombres[which]
                prefs.edit().putString(KEY_TTS_VOICE, nombre).apply()
                if (idioma == languageMode()) applyVoice(nombre)
                val elegida = if (nombre.isBlank()) {
                    bestVoice(motor.voices, idioma)
                } else {
                    voces.firstOrNull { it.name == nombre }
                }
                if (elegida != null) previewVoice(elegida, idioma)
            }
            .setNegativeButton(R.string.settings_voice_close, null)
            .setOnDismissListener { previewTts?.stop() }
            .show()
    }

    private fun previewVoice(voz: Voice, idioma: String) {
        tts?.stop()
        onPreviewStarting()
        val frase = activity.getString(
            if (idioma == "en") R.string.settings_test_phrase_en
            else R.string.settings_test_phrase
        )
        val motor = previewTts
        if (motor != null) {
            applyPreviewVoice(motor, voz, frase)
            return
        }
        previewTts = TextToSpeech(activity) { status ->
            val nuevo = previewTts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            applyPreviewVoice(nuevo, voz, frase)
        }
    }

    private fun applyPreviewVoice(motor: TextToSpeech, voz: Voice, frase: String) {
        runCatching {
            motor.setLanguage(voz.locale)
            motor.voice = voz
            motor.setSpeechRate(speechRate)
            motor.speak(
                frase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "preview-${System.currentTimeMillis()}"
            )
            Log.i("Samantha", "muestra de voz: ${voz.name} | ${voz.locale}")
        }
    }

    private fun applyVoice(nombre: String) {
        val motor = tts ?: return
        val voz = if (nombre.isBlank()) {
            bestVoice(motor.voices, languageMode())
        } else {
            motor.voices?.firstOrNull { it.name == nombre }
        }
        if (voz != null) {
            runCatching { motor.voice = voz }
            motor.setSpeechRate(speechRate)
            Log.i("Samantha", "voz TTS elegida: ${voz.name} | ${voz.locale}")
        }
    }

    fun restart() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
        initTts()
    }

    fun updateSpeechRate(value: Float) {
        speechRate = value.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
        previewTts?.setSpeechRate(speechRate)
    }

    fun persistSpeechRate() {
        prefs.edit().putFloat(KEY_TTS_RATE, speechRate).apply()
    }

    // ── habla ─────────────────────────────────────────────────────────

    fun speak(texto: String) {
        val motor = tts
        if (!ttsReady || motor == null) {
            pendingSpeech = texto
            onNotReady()
            return
        }
        onSpeaking()
        val id = "samantha-${System.currentTimeMillis()}"
        ultimaUtterance = id
        if (reveladoConVoz && respuestaActual == texto) {
            startRevealFallback()
        }
        motor.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finishSpeaking() {
        revealAll()
        onFinished()
    }

    fun stop() {
        tts?.stop()
        previewTts?.stop()
    }

    fun pause() {
        tts?.stop()
        previewTts?.stop()
        revealAll()
    }

    fun stopPreview() {
        previewTts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun usesNetworkVoice(): Boolean =
        tts?.voice?.isNetworkConnectionRequired == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        previewTts?.stop()
        previewTts?.shutdown()
        previewTts = null
        cancelReveal()
    }

    // ── tarjeta y revelado ────────────────────────────────────────────

    fun showResponse(
        texto: String,
        colorRes: Int,
        revealWithVoice: Boolean = true,
    ) {
        cancelReveal()
        respuestaActual = texto
        respuestaColor = ContextCompat.getColor(activity, colorRes)
        response.setTextColor(respuestaColor)
        if (revealWithVoice && ttsReady && tts != null) {
            reveladoConVoz = true
            reveladoHasta = 0
            if (texto.isEmpty()) {
                response.text = ""
            } else {
                val sp = SpannableString(texto).apply {
                    setSpan(
                        ForegroundColorSpan(Color.TRANSPARENT),
                        0,
                        texto.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                response.text = sp
            }
        } else {
            reveladoConVoz = false
            reveladoHasta = texto.length
            response.text = texto
        }
        responseScroll.visibility = View.VISIBLE
        responseScroll.scrollTo(0, 0)
        onResponseShown()
    }

    fun cancelReveal() {
        cancelRevealFallback()
        reveladoConVoz = false
    }

    fun revealAll() {
        cancelRevealFallback()
        if (!reveladoConVoz) return
        reveladoConVoz = false
        if (respuestaActual.isEmpty()) return
        reveladoHasta = respuestaActual.length
        response.text = respuestaActual
        response.setTextColor(respuestaColor)
    }

    private fun revealIntervalMs(): Long =
        (300f / speechRate).toLong().coerceIn(110L, 650L)

    private fun startRevealFallback() {
        if (!reveladoConVoz || respuestaActual.isEmpty()) return
        cancelRevealFallback()
        reveladoFallbackActivo = true
        reveladoFallbackIndice = 0
        reveladoPalabras = Regex("\\S+")
            .findAll(respuestaActual)
            .map { it.range }
            .toList()
        response.postDelayed(reveladoFallbackRunnable, 160L)
    }

    private fun cancelRevealFallback() {
        reveladoFallbackActivo = false
        response.removeCallbacks(reveladoFallbackRunnable)
    }

    private fun revealUntil(start: Int, end: Int) {
        if (!reveladoConVoz) return
        cancelRevealFallback()
        applyReveal(start, end)
    }

    private fun applyReveal(start: Int, end: Int) {
        if (!reveladoConVoz || respuestaActual.isEmpty()) return
        val fin = end.coerceIn(0, respuestaActual.length)
        val inicio = start.coerceIn(0, fin)
        if (fin > reveladoHasta) reveladoHasta = fin

        val sp = SpannableString(respuestaActual)
        if (reveladoHasta < respuestaActual.length) {
            sp.setSpan(
                ForegroundColorSpan(Color.TRANSPARENT),
                reveladoHasta,
                respuestaActual.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (reveladoHasta > 0) {
            sp.setSpan(
                ForegroundColorSpan(respuestaColor),
                0,
                reveladoHasta,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (fin > inicio) {
            sp.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(activity, R.color.cream)),
                inicio,
                fin,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sp.setSpan(
                BackgroundColorSpan(0x3322D3EE),
                inicio,
                fin,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        response.text = sp
        response.post {
            val layout = response.layout ?: return@post
            val linea = layout.getLineForOffset(fin.coerceAtMost(respuestaActual.length))
            responseScroll.scrollTo(0, layout.getLineTop(linea).coerceAtLeast(0))
        }
    }

    private companion object {
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_RATE = "tts_rate"
    }
}
