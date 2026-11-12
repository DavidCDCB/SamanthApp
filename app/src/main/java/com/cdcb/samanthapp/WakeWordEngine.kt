package com.cdcb.samanthapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Preferencias compartidas por la actividad y el servicio de wake word. */
object WakeWordSettings {
    const val PREFS = "samantha"
    const val KEY_ENABLED = "wake_word_enabled"
    const val KEY_BACKGROUND = "wake_word_background"
    const val EXTRA_WAKE_WORD = "wake_word"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun background(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND, false)
}

/**
 * Detector local de «Hola Samantha» con Vosk. Mantiene el modelo cargado y
 * crea un SpeechService nuevo cada vez que empieza a escuchar; al detenerse
 * libera el micrófono para que SpeechRecognizer pueda usarlo.
 */
class WakeWordEngine(
    context: Context,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var generation = 0
    private var fallos = 0
    private var ultimaActivacion = 0L

    @Volatile
    private var running = false

    @Volatile
    private var loading = false

    /** Carga el modelo (bloqueante). Devuelve false si no está disponible. */
    fun cargar(): Boolean {
        model?.let { return true }
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = VoskModelHolder.obtener(appContext)
            true
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo cargar el modelo Vosk: ${e.message}")
            onError("No se pudo cargar el modelo de «Hola Samantha».")
            false
        }
    }

    /** Empieza a escuchar; si el modelo no está cargado, lo carga aparte. */
    fun iniciar() {
        running = true
        fallos = 0
        if (service != null || loading) return
        Log.i(TAG, "iniciar escucha")
        val actual = model
        if (actual == null) {
            cargarEnSegundoPlano()
        } else {
            iniciarServicio(actual)
        }
    }

    /** Detiene la escucha y libera el micrófono. */
    fun detener() {
        running = false
        detenerServicio()
    }

    fun liberar() {
        detener()
        model = null
    }

    private fun cargarEnSegundoPlano() {
        if (loading) return
        loading = true
        Thread {
            val ok = cargar()
            main.post {
                loading = false
                val actual = model
                if (ok && running && actual != null) {
                    iniciarServicio(actual)
                }
            }
        }.start()
    }

    private fun iniciarServicio(actual: Model) {
        if (!running || service != null) return
        val gen = ++generation
        try {
            val rec = try {
                Recognizer(actual, 16000f, GRAMMAR)
            } catch (e: Exception) {
                Log.w(TAG, "gramática no disponible, uso reconocedor libre: ${e.message}")
                Recognizer(actual, 16000f)
            }
            recognizer = rec
            val nuevo = SpeechService(rec, 16000f)
            service = nuevo
            Log.i(TAG, "Vosk escuchando")
            nuevo.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (gen != generation || !running) return
                    fallos = 0
                    val texto = textoDe(hypothesis)
                    if (texto.isNotBlank()) Log.d(TAG, "parcial: $texto")
                    evaluar(texto)
                }

                override fun onResult(hypothesis: String?) {
                    if (gen != generation || !running) return
                    fallos = 0
                    val texto = textoDe(hypothesis)
                    if (texto.isNotBlank()) Log.d(TAG, "resultado: $texto")
                    evaluar(texto)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (gen != generation || !running) return
                    fallos = 0
                    val texto = textoDe(hypothesis)
                    if (texto.isNotBlank()) Log.d(TAG, "final: $texto")
                    evaluar(texto)
                }

                override fun onError(exception: Exception?) {
                    if (gen != generation) return
                    Log.w(TAG, "Vosk: ${exception?.message}")
                    detenerServicio()
                    reintentar("No se pudo usar el micrófono para «Hola Samantha».")
                }

                override fun onTimeout() = Unit
            })
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo iniciar Vosk: ${e.message}")
            detenerServicio()
            reintentar("No se pudo iniciar la escucha de «Hola Samantha».")
        }
    }

    private fun reintentar(mensaje: String) {
        if (!running) return
        fallos++
        if (fallos > MAX_FALLOS) {
            running = false
            onError(mensaje)
            return
        }
        main.postDelayed({
            val actual = model
            if (running && service == null && actual != null) {
                iniciarServicio(actual)
            }
        }, 1500L)
    }

    private fun detenerServicio() {
        generation++
        val actual = service
        val rec = recognizer
        service = null
        recognizer = null
        try {
            actual?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop Vosk: ${e.message}")
        }
        try {
            actual?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown Vosk: ${e.message}")
        }
        try {
            rec?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close recognizer: ${e.message}")
        }
    }

    private fun evaluar(texto: String) {
        if (!WakeWordMatcher.esHolaSamantha(texto)) return
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaActivacion < COOLDOWN_MS) return
        ultimaActivacion = ahora
        Log.i(TAG, "wake word detectada: '$texto'")
        running = false
        detenerServicio()
        onWake()
    }

    private fun textoDe(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try {
            val obj = JSONObject(hypothesis)
            obj.optString("partial").ifBlank { obj.optString("text") }
        } catch (_: Exception) {
            hypothesis
        }
    }

    companion object {
        private const val TAG = "SamanthaVosk"
        private const val MAX_FALLOS = 5
        private const val COOLDOWN_MS = 2_500L
        private const val GRAMMAR =
            "[\"hola samantha\",\"hola samanta\",\"oye samantha\",\"oye samanta\",\"[unk]\"]"
    }
}
