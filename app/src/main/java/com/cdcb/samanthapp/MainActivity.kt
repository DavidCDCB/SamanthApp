package com.cdcb.samanthapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Dialog
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.cdcb.samanthapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pantalla única: orbe + botón para hablar.
 *
 * Ciclo completo: reconocimiento de voz del sistema → modelo de OpenCode Go
 * (API compatible con OpenAI) → voz del sistema (TTS).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var stopping = false
    private var history = mutableListOf<Message>()
    private var expectingName = false
    private var nameAskedThisSession = false
    private var pendingSpeech: String? = null
    private lateinit var memory: MemoryStore
    private lateinit var appConfig: SamanthaConfigData
    private var autoListen = false
    private var autoTurn = false
    private var autoListenTtsRetries = 0
    private var ultimaUtterance = ""
    private var settingsDialog: Dialog? = null
    private var previewTts: TextToSpeech? = null
    private var languageMode = "es"

    private var wakeWordEnabled = false
    private var wakeWordBackground = false
    private var wakeWordEngine: WakeWordEngine? = null
    private var wakeLaunch = false
    private var enPrimerPlano = false
    private var animatedBackground = "on"
    private var keepScreenOn = true
    private var autoOpenStatusView: TextView? = null
    private var autoOpenButtonView: MaterialButton? = null
    private var wakeModeGroupView: MaterialButtonToggleGroup? = null
    private val wakeWordRunnable = Runnable { iniciarWakeWordSiPuede() }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                showError(getString(R.string.permission_needed))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.settings_wake_notification_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        wakeLaunch = intent?.getBooleanExtra(WakeWordSettings.EXTRA_WAKE_WORD, false) == true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pantallaCompleta(window)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        applyInsets()

        appConfig = SamanthaConfig.load(this)
        memory = MemoryStore(this, appConfig.historyLimit)
        history = memory.history()
        autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, false)
        languageMode = prefs.getString(KEY_LANGUAGE, "es")
            ?.takeIf { it == "en" } ?: "es"
        wakeWordEnabled = prefs.getBoolean(WakeWordSettings.KEY_ENABLED, false)
        wakeWordBackground = prefs.getBoolean(WakeWordSettings.KEY_BACKGROUND, false)
        wakeWordEngine = WakeWordEngine(
            this,
            onWake = { alDetectarWakeWord() },
            onError = { mensaje ->
                Log.w("Samantha", mensaje)
                if (!isFinishing) {
                    Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
                }
            }
        )

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(recognitionListener)
        speech = recognizer

        binding.orb.setOnClickListener { toggleListening() }
        binding.settingsButton.setOnClickListener { openSettings() }
        animatedBackground = prefs.getString(KEY_ANIMATED_BG, "on")
            ?.takeIf { it == "low" || it == "off" } ?: "on"
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        binding.backdrop.setMode(animatedBackground)
        // Halo, anillos y ondas nacen del centro real del orbe, no del centro
        // de la pantalla: si la tarjeta de respuesta lo empuja, se recalcula.
        binding.orb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            sincronizarFocoFondo()
        }
        binding.root.post { sincronizarFocoFondo() }
        updateUi(estadoReposo())

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.recognition_unavailable))
        }

        initTts()
        if (wakeLaunch) {
            binding.root.postDelayed({ lanzarDesdeWakeWord() }, 800L)
        }
        // Primer arranque sin API key: abre Ajustes para configurarla.
        if (apiKey().isBlank()) {
            binding.root.postDelayed({
                if (!isFinishing && apiKey().isBlank() &&
                    settingsDialog?.isShowing != true
                ) {
                    openSettings()
                }
            }, 600L)
        }
    }

    // ── TTS ────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            val motor = tts ?: return@TextToSpeech
            val locale = if (languageMode == "en") {
                Locale.US
            } else {
                Locale("es", "CO")
            }
            var resultado = motor.setLanguage(locale)
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                resultado = motor.setLanguage(
                    if (languageMode == "en") Locale.ENGLISH else Locale("es", "ES")
                )
            }
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                if (languageMode != "en") resultado = motor.setLanguage(Locale("es"))
            }
            if (resultado == TextToSpeech.LANG_MISSING_DATA ||
                resultado == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady = false
                return@TextToSpeech
            }
            // Mejor voz del idioma activo (prefiere red/neural y acento local).
            val voz = vozGuardada(motor) ?: mejorVoz(motor.voices, languageMode)
            if (voz != null) {
                motor.voice = voz
                Log.i(
                    "Samantha",
                    "voz TTS: ${voz.name} | ${voz.locale} | " +
                        "quality=${voz.quality} | red=${voz.isNetworkConnectionRequired}"
                )
            }
            motor.setSpeechRate(1.05f)
            motor.setPitch(1.0f)
            motor.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == ultimaUtterance) {
                        runOnUiThread { finishSpeaking() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == ultimaUtterance) {
                        runOnUiThread { finishSpeaking() }
                    }
                }
            })
            pendingSpeech?.let { pendiente ->
                pendingSpeech = null
                runOnUiThread { speak(pendiente) }
            }
        }
    }

    private fun vozGuardada(motor: TextToSpeech): Voice? {
        val nombre = prefs.getString(KEY_TTS_VOICE, "").orEmpty()
        if (nombre.isBlank()) return null
        return motor.voices?.firstOrNull {
            it.name == nombre && it.locale.language == languageMode
        }
    }

    /** Prefiere calidad y red; desempata por acento del idioma activo. */
    private fun mejorVoz(voces: Set<Voice>?, idioma: String): Voice? {
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
            } else {
                when {
                    voz.locale.country.equals("US", true) -> puntos += 60
                    voz.locale.country.equals("GB", true) -> puntos += 30
                }
            }
            if (voz.isNetworkConnectionRequired) puntos += 25
            puntos
        }
    }

    private fun elegirVoz(idioma: String = languageMode) {
        val motor = tts
        if (motor == null) {
            testResultDePrueba("La voz todavía se está cargando…", corto = true)
            return
        }
        val voces = motor.voices
            ?.filter { it.locale.language == idioma }
            ?.sortedWith(
                compareBy(
                    { !it.isNetworkConnectionRequired },
                    { it.locale.toString() },
                    { it.name }
                )
            )
            ?: emptyList()
        if (voces.isEmpty()) {
            testResultDePrueba("No hay voces disponibles para ese idioma.", corto = true)
            return
        }

        val etiquetas = mutableListOf(getString(R.string.settings_voice_auto))
        val nombres = mutableListOf("")
        for (voz in voces) {
            val red = if (voz.isNetworkConnectionRequired) " · online" else " · local"
            etiquetas += "${voz.name} · ${voz.locale}$red"
            nombres += voz.name
        }

        val actual = if (idioma == languageMode) {
            prefs.getString(KEY_TTS_VOICE, "").orEmpty()
        } else {
            ""
        }
        val marcada = nombres.indexOf(actual).coerceAtLeast(0)
        val etiquetaIdioma = if (idioma == "en") "English" else "Español"

        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.settings_voice)} · $etiquetaIdioma")
            .setSingleChoiceItems(etiquetas.toTypedArray(), marcada) { _, which ->
                val nombre = nombres[which]
                prefs.edit().putString(KEY_TTS_VOICE, nombre).apply()
                // Si el idioma elegido aún no está guardado, la voz se aplica
                // al pulsar Guardar (reiniciarTts).
                if (idioma == languageMode) aplicarVoz(nombre)
                val elegida = if (nombre.isBlank()) {
                    mejorVoz(motor.voices, idioma)
                } else {
                    voces.firstOrNull { it.name == nombre }
                }
                if (elegida != null) previsualizarVoz(elegida, idioma)
            }
            .setNegativeButton(R.string.settings_voice_close, null)
            .setOnDismissListener { previewTts?.stop() }
            .show()
    }

    /** Muestra la frase de prueba con la voz elegida, sin tocar el motor principal. */
    private fun previsualizarVoz(voz: Voice, idioma: String) {
        // Si Samantha está hablando, se calla para dejar oír la muestra.
        tts?.stop()
        if (!enReposo(binding.orb.state)) updateUi(estadoReposo())
        binding.root.removeCallbacks(autoListenRunnable)
        val frase = getString(
            if (idioma == "en") R.string.settings_test_phrase_en
            else R.string.settings_test_phrase
        )
        val motor = previewTts
        if (motor != null) {
            aplicarVozPreview(motor, voz, frase)
            return
        }
        previewTts = TextToSpeech(this) { status ->
            val nuevo = previewTts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            aplicarVozPreview(nuevo, voz, frase)
        }
    }

    private fun aplicarVozPreview(motor: TextToSpeech, voz: Voice, frase: String) {
        runCatching {
            motor.setLanguage(voz.locale)
            motor.voice = voz
            motor.speak(
                frase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "preview-${System.currentTimeMillis()}"
            )
            Log.i("Samantha", "muestra de voz: ${voz.name} | ${voz.locale}")
        }
    }

    private fun aplicarVoz(nombre: String) {
        val motor = tts ?: return
        val voz = if (nombre.isBlank()) {
            mejorVoz(motor.voices, languageMode)
        } else {
            motor.voices?.firstOrNull { it.name == nombre }
        }
        if (voz != null) {
            runCatching { motor.voice = voz }
            Log.i("Samantha", "voz TTS elegida: ${voz.name} | ${voz.locale}")
        }
    }

    private fun reiniciarTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
        initTts()
    }

    private fun speak(texto: String) {
        val motor = tts
        if (!ttsReady || motor == null) {
            // El TTS puede tardar en inicializar; se dice en cuanto esté listo.
            pendingSpeech = texto
            updateUi(estadoReposo())
            return
        }
        updateUi(OrbView.State.SPEAKING)
        val id = "samantha-${System.currentTimeMillis()}"
        ultimaUtterance = id
        motor.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finishSpeaking() {
        // Primero el auto-listen para que su ventana gane la carrera contra
        // el wake word: si el micrófono se reabre solo, Vosk no compite.
        Log.i("Samantha", "TTS terminó; auto-listen=$autoListen")
        scheduleAutoListen()
        updateUi(estadoReposo())
    }

    private val autoListenRunnable = Runnable { maybeAutoListen() }

    private fun scheduleAutoListen() {
        if (!autoListen) return
        autoListenTtsRetries = 0
        binding.root.removeCallbacks(autoListenRunnable)
        // 200 ms de margen para que no se cuele la cola del TTS. Si la voz
        // es de red, el audio puede llegar después del onDone: se añade
        // margen extra para no oír la propia respuesta en el micrófono.
        val extraRed = if (tts?.voice?.isNetworkConnectionRequired == true) {
            AUTO_LISTEN_NETWORK_GUARD_MS
        } else {
            0L
        }
        binding.root.postDelayed(autoListenRunnable, AUTO_LISTEN_DELAY_MS + extraRed)
    }

    private fun maybeAutoListen() {
        if (!autoListen || listening || isFinishing) return
        // Algunos motores TTS llaman onDone antes de terminar el audio.
        // No se abre el micrófono hasta que el motor deje de hablar.
        if (tts?.isSpeaking == true) {
            autoListenTtsRetries++
            if (autoListenTtsRetries <= AUTO_LISTEN_TTS_MAX_RETRIES) {
                Log.i("Samantha", "auto-listen: espero a que termine el TTS")
                binding.root.removeCallbacks(autoListenRunnable)
                binding.root.postDelayed(autoListenRunnable, AUTO_LISTEN_TTS_RETRY_MS)
                return
            }
        }
        autoListenTtsRetries = 0
        if (!enReposo(binding.orb.state)) return
        val permiso = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )
        if (permiso != PackageManager.PERMISSION_GRANTED) return
        Log.i("Samantha", "auto-listen: reabro el micrófono")
        startListening(automatic = true)
    }

    // ── palabra de activación ──────────────────────────────────────────

    /** Decide si Vosk debe estar escuchando según el estado y los ajustes. */
    private fun syncWakeWord(inmediato: Boolean = false) {
        binding.root.removeCallbacks(wakeWordRunnable)
        if (!wakeWordEnabled || wakeLaunch || !enPrimerPlano) {
            wakeWordEngine?.detener()
            return
        }
        val libre = enReposo(binding.orb.state) &&
            !listening && settingsDialog?.isShowing != true
        if (!libre) {
            wakeWordEngine?.detener()
            return
        }
        binding.root.postDelayed(
            wakeWordRunnable,
            if (inmediato) WAKE_WORD_STARTUP_DELAY_MS else WAKE_WORD_DELAY_MS
        )
    }

    private fun iniciarWakeWordSiPuede() {
        if (!wakeWordEnabled || wakeLaunch || listening) return
        if (!enReposo(binding.orb.state)) return
        if (settingsDialog?.isShowing == true) return
        Log.i("Samantha", "wake word: inicio de la escucha")
        wakeWordEngine?.iniciar()
    }

    private fun alDetectarWakeWord() {
        if (isFinishing || listening || !enPrimerPlano) return
        if (!enReposo(binding.orb.state)) return
        binding.orb.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.backdrop.ripple()
        Log.i("Samantha", "wake word: abro el micrófono")
        startListening(automatic = true)
    }

    private fun lanzarDesdeWakeWord() {
        if (!wakeLaunch || isFinishing) return
        wakeLaunch = false
        if (!listening && enReposo(binding.orb.state)) {
            startListening(automatic = true)
        } else {
            syncWakeWord()
        }
    }

    private fun iniciarServicioWakeWord() {
        if (!wakeWordEnabled || !wakeWordBackground) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java)
            )
        } catch (e: Exception) {
            Log.w("Samantha", "no se pudo iniciar el servicio de wake word: ${e.message}")
            Toast.makeText(
                this,
                R.string.settings_wake_service_error,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pararServicioWakeWord() {
        stopService(Intent(this, WakeWordService::class.java))
    }

    private fun accionServicioWakeWord(accion: String) {
        if (!WakeWordService.running) return
        try {
            startService(
                Intent(this, WakeWordService::class.java).setAction(accion)
            )
        } catch (e: Exception) {
            Log.w("Samantha", "acción del servicio de wake word: ${e.message}")
        }
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Estado del permiso «Mostrar encima de otras apps» para abrir sola. */
    private fun actualizarPermisoAutoOpen() {
        val status = autoOpenStatusView ?: return
        val button = autoOpenButtonView ?: return
        val permitido = Settings.canDrawOverlays(this)
        status.visibility = View.VISIBLE
        button.visibility = View.VISIBLE
        status.text = getString(
            if (permitido) R.string.settings_wake_auto_open_granted
            else R.string.settings_wake_auto_open_missing
        )
        button.text = getString(
            if (permitido) R.string.settings_wake_auto_open_button_granted
            else R.string.settings_wake_auto_open_button
        )
        button.isEnabled = !permitido
    }

    // ── reconocimiento ────────────────────────────────────────────────

    private fun toggleListening() {
        binding.root.removeCallbacks(autoListenRunnable)
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        binding.orb.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.backdrop.ripple()

        if (listening) {
            stopping = true
            listening = false
            speech?.stopListening()
            updateUi(OrbView.State.TRANSCRIBING)
            return
        }

        // Mientras piensa o transcribe no se abre otro turno; al hablar, el
        // toque corta la voz y empieza a escuchar (barge-in).
        val estado = binding.orb.state
        when (estado) {
            OrbView.State.THINKING,
            OrbView.State.TRANSCRIBING -> return
            else -> Unit
        }

        if (estado == OrbView.State.SPEAKING) {
            // Interrumpir una respuesta también la borra de la conversación:
            // ese intercambio no se guarda ni se envía al modelo después.
            tts?.stop()
            descartarUltimoIntercambio()
            updateUi(estadoReposo())
        }

        val permiso = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )
        if (permiso == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Barge-in: si se interrumpe la respuesta tocando el orbe, el intercambio
     * interrumpido no debe quedar en la memoria ni en el próximo turno.
     */
    private fun descartarUltimoIntercambio() {
        val ultimo = history.lastOrNull() ?: return
        if (ultimo.role != "assistant") return
        val preguntoNombre = Regex("(?i)(te llamas|tu nombre)")
            .containsMatchIn(ultimo.content)
        val antes = history.size
        history = HistoryRules.dropLastExchange(history).toMutableList()
        if (history.size == antes) return
        // Si la respuesta interrumpida era la pregunta del nombre, se vuelve
        // a preguntar en el siguiente turno en vez de esperar una respuesta.
        if (preguntoNombre) {
            expectingName = false
            nameAskedThisSession = false
        }
        memory.saveHistory(history)
        Log.i(
            "Samantha",
            "barge-in: intercambio descartado, quedan ${history.size} mensajes"
        )
    }

    private fun startListening(automatic: Boolean = false) {
        binding.root.removeCallbacks(autoListenRunnable)
        autoTurn = automatic
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.recognition_unavailable))
            return
        }
        if (apiKey().isBlank()) {
            showError(getString(R.string.settings_key_required))
            openSettings()
            return
        }

        tts?.stop()
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        stopping = false
        listening = true
        binding.responseScroll.visibility = View.GONE
        binding.transcript.visibility = View.GONE
        updateUi(OrbView.State.LISTENING)
        speech?.startListening(recognizerIntent())
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val codigo = if (languageMode == "en") "en-US" else "es-CO"
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, codigo)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, codigo)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            stopping = false
            updateUi(OrbView.State.LISTENING)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            binding.orb.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            binding.backdrop.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            if (rmsdB > 3f && binding.orb.state == OrbView.State.LISTENING) {
                binding.orb.state = OrbView.State.HEARING
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
            updateUi(OrbView.State.TRANSCRIBING)
        }

        override fun onError(error: Int) {
            val fueCancelado = stopping
            val eraAuto = autoTurn
            stopping = false
            listening = false
            autoTurn = false
            binding.orb.setLevel(0f)
            if (fueCancelado) {
                updateUi(estadoReposo())
            } else if (
                eraAuto &&
                (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                // Una sola ventana: si no hablas, vuelve a esperar un toque
                // o a la palabra de activación, sin abrir otra ventana.
                Log.i("Samantha", "auto-listen: sin voz, vuelvo a esperar")
                updateUi(estadoReposo())
            } else {
                showError(errorMessage(error))
            }
        }

        override fun onResults(results: Bundle?) {
            stopping = false
            listening = false
            autoTurn = false
            binding.orb.setLevel(0f)

            val texto = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (texto.isEmpty()) {
                showError(getString(R.string.error_no_match))
                return
            }

            binding.transcript.text = texto
            binding.transcript.visibility = View.VISIBLE
            updateUi(OrbView.State.THINKING)
            handleMemoryOrModel(texto)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texto = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (texto.isNotEmpty()) {
                binding.transcript.text = texto
                binding.transcript.visibility = View.VISIBLE
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // ── memoria y modelo ─────────────────────────────────────────────

    private fun aprendidoElNombre(texto: String) {
        val nombre = NameExtractor.extract(texto, allowSoy = memory.userName.isBlank())
            ?: if (expectingName) NameExtractor.extractShortAnswer(texto) else null
        if (nombre != null && !nombre.equals(memory.userName, ignoreCase = true)) {
            memory.userName = nombre
            nameAskedThisSession = true
            expectingName = false
        }
    }

    private fun handleMemoryOrModel(texto: String) {
        when (val comando = MemoryCommands.parse(texto)) {
            is MemoryCommands.Result.Remember -> {
                aprendidoElNombre(texto)
                val antes = memory.facts().size
                Log.i("Samantha", "memoria: guardar '${comando.fact}'")
                memory.addFact(comando.fact)
                val despues = memory.facts().size
                Log.i("Samantha", "memoria: $despues recuerdos")
                val respuesta = if (despues > antes) {
                    getString(R.string.memory_saved)
                } else {
                    getString(R.string.memory_already)
                }
                finishLocalTurn(texto, respuesta)
            }

            is MemoryCommands.Result.Forget -> {
                Log.i("Samantha", "memoria: olvidar '${comando.query}'")
                val (restantes, borrados) = memory.forget(comando.query)
                Log.i("Samantha", "memoria: $borrados borrados, quedan ${restantes.size}")
                val respuesta = if (borrados > 0) {
                    getString(R.string.memory_forgotten)
                } else {
                    getString(R.string.memory_unknown)
                }
                finishLocalTurn(texto, respuesta)
            }

            null -> {
                aprendidoElNombre(texto)
                askModel(texto)
            }
        }
    }

    private fun finishLocalTurn(texto: String, respuesta: String) {
        history.add(Message("user", texto))
        history.add(Message("assistant", respuesta))
        if (history.size > appConfig.historyLimit) {
            history = history.takeLast(appConfig.historyLimit).toMutableList()
        }
        memory.saveHistory(history)
        mostrarRespuesta(respuesta, R.color.cyan)
        speak(respuesta)
    }

    private fun askModel(texto: String) {
        val key = apiKey()
        val modelo = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: appConfig.model
        val nombre = memory.userName
        val recuerdos = memory.facts()
        // Si no hay nombre y no se ha pedido en esta sesión, el modelo lo pregunta.
        val pedirNombre = nombre.isBlank() && !nameAskedThisSession

        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    SamanthaApi(
                        appConfig.baseUrl,
                        key,
                        modelo,
                        sessionId(),
                        SamanthaApi.loadPersona(applicationContext),
                        appConfig.reasoningEffort,
                        appConfig.maxTokens,
                        appConfig.temperature,
                        appConfig.firecrawlApiKey,
                        appConfig.firecrawlMaxResults,
                        appConfig.firecrawlLang,
                        appConfig.firecrawlCountry,
                        appConfig.firecrawlTimeoutMs,
                        appConfig.historyLimit,
                    ).chat(history, texto, nombre, recuerdos, pedirNombre, languageMode)
                }
            }

            resultado.onSuccess { respuestaCruda ->
                if (respuestaCruda.isBlank()) {
                    showError(getString(R.string.error_empty_reply))
                    return@onSuccess
                }
                var respuesta = respuestaCruda
                if (memory.userName.isBlank()) {
                    val pregunto = Regex("(?i)(te llamas|tu nombre)")
                        .containsMatchIn(respuesta)
                    if (pregunto) {
                        nameAskedThisSession = true
                        expectingName = true
                    } else if (pedirNombre) {
                        // El modelo ignoró la instrucción: se añade la pregunta
                        // para que el nombre no se quede sin pedir.
                        respuesta = respuesta.trimEnd() +
                            getString(R.string.name_question_fallback)
                        nameAskedThisSession = true
                        expectingName = true
                    }
                }
                history.add(Message("user", texto))
                history.add(Message("assistant", respuesta))
                if (history.size > appConfig.historyLimit) {
                    history = history.takeLast(appConfig.historyLimit).toMutableList()
                }
                memory.saveHistory(history)
                mostrarRespuesta(respuesta, R.color.cyan)
                speak(respuesta)
            }.onFailure { e ->
                showError(llmError(e))
            }
        }
    }

    private fun llmError(e: Throwable): String = when {
        e is SamanthaApi.ApiException && e.code in 401..403 ->
            getString(R.string.error_auth)
        e is UnknownHostException || e is ConnectException ->
            getString(R.string.error_network)
        e is SocketTimeoutException -> getString(R.string.error_timeout)
        else -> getString(R.string.error_generic_llm, e.message?.take(80) ?: "")
    }

    // ── ajustes ───────────────────────────────────────────────────────

    private fun actualizarEstadoModelo(view: TextView) {
        view.text = if (VoskModelStore.listo(this)) {
            getString(R.string.settings_wake_model_ready)
        } else {
            getString(R.string.settings_wake_model_missing)
        }
    }

    private fun descargarModelo(onDone: (Boolean) -> Unit) {
        val vista = layoutInflater.inflate(R.layout.dialog_download, null)
        val progreso = vista.findViewById<LinearProgressIndicator>(R.id.downloadProgress)
        val etiqueta = vista.findViewById<TextView>(R.id.downloadLabel)
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_wake_download_title)
            .setView(vista)
            .setCancelable(false)
            .create()
        dialogo.show()
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    VoskModelStore.asegurar(applicationContext) { pct ->
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            if (pct < 0) {
                                progreso.isIndeterminate = true
                            } else {
                                progreso.isIndeterminate = false
                                progreso.setProgressCompat(pct, true)
                                etiqueta.text = getString(
                                    R.string.settings_wake_downloading,
                                    pct
                                )
                            }
                        }
                    }
                }
            }
            if (!isFinishing) dialogo.dismiss()
            resultado.fold(
                onSuccess = {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.settings_wake_download_ok,
                        Toast.LENGTH_SHORT
                    ).show()
                    onDone(true)
                },
                onFailure = {
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.settings_wake_download_error,
                            it.message ?: ""
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    onDone(false)
                }
            )
        }
    }

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        val vista = layoutInflater.inflate(R.layout.dialog_settings, null)
        val keyInput = vista.findViewById<EditText>(R.id.keyInput)
        val modelInput = vista.findViewById<EditText>(R.id.modelInput)
        val testModelButton = vista.findViewById<MaterialButton>(R.id.testModelButton)
        val saveButton = vista.findViewById<MaterialButton>(R.id.saveButton)
        val cancelButton = vista.findViewById<MaterialButton>(R.id.cancelButton)
        val memoryInfo = vista.findViewById<TextView>(R.id.memoryInfo)
        val clearButton = vista.findViewById<MaterialButton>(R.id.clearMemoryButton)
        val voiceButton = vista.findViewById<MaterialButton>(R.id.voiceButton)
        val autoSwitch = vista.findViewById<MaterialSwitch>(R.id.autoListenSwitch)
        val keepScreenSwitch = vista.findViewById<MaterialSwitch>(R.id.keepScreenSwitch)
        val backgroundGroup = vista.findViewById<MaterialButtonToggleGroup>(R.id.backgroundGroup)
        val languageGroup = vista.findViewById<MaterialButtonToggleGroup>(R.id.languageGroup)
        val closeButton = vista.findViewById<ImageButton>(R.id.closeSettings)
        val wakeSwitch = vista.findViewById<MaterialSwitch>(R.id.wakeWordSwitch)
        val wakeOptions = vista.findViewById<View>(R.id.wakeWordOptions)
        val wakeModeGroup = vista.findViewById<MaterialButtonToggleGroup>(R.id.wakeModeGroup)
        val wakeModelStatus = vista.findViewById<TextView>(R.id.wakeModelStatus)
        val autoOpenButton = vista.findViewById<MaterialButton>(R.id.autoOpenButton)
        val autoOpenStatus = vista.findViewById<TextView>(R.id.autoOpenStatus)

        autoOpenStatusView = autoOpenStatus
        autoOpenButtonView = autoOpenButton
        wakeModeGroupView = wakeModeGroup

        keyInput.setText(apiKey())
        modelInput.setText(
            prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: appConfig.model
        )
        memoryInfo.text = getString(R.string.settings_memory_info, memory.facts().size)
        Log.i("Samantha", "ajustes: ${memory.facts().size} recuerdos")
        autoSwitch.isChecked = autoListen
        keepScreenSwitch.isChecked = keepScreenOn
        backgroundGroup.check(
            when (animatedBackground) {
                "low" -> R.id.backgroundLow
                "off" -> R.id.backgroundOff
                else -> R.id.backgroundOn
            }
        )
        languageGroup.check(if (languageMode == "en") R.id.languageEn else R.id.languageEs)
        wakeSwitch.isChecked = wakeWordEnabled
        wakeOptions.visibility = if (wakeWordEnabled) View.VISIBLE else View.GONE
        wakeModeGroup.check(
            if (wakeWordBackground) R.id.wakeModeBackground else R.id.wakeModeForeground
        )
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            wakeOptions.visibility = if (checked) View.VISIBLE else View.GONE
        }
        wakeModeGroup.addOnButtonCheckedListener { _, _, _ ->
            actualizarPermisoAutoOpen()
        }
        autoOpenButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w("Samantha", "no se abrió el permiso de overlay: ${e.message}")
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        actualizarEstadoModelo(wakeModelStatus)
        actualizarPermisoAutoOpen()

        val dialogo = Dialog(this, R.style.Theme_SamanthApp_FullScreenDialog)
        dialogo.setContentView(vista)
        dialogo.setCanceledOnTouchOutside(false)
        settingsDialog = dialogo
        dialogo.setOnDismissListener {
            settingsDialog = null
            autoOpenStatusView = null
            autoOpenButtonView = null
            wakeModeGroupView = null
            previewTts?.stop()
            pantallaCompleta(window)
            syncWakeWord()
        }
        closeButton.setOnClickListener { dialogo.dismiss() }

        clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_clear_title)
                .setMessage(R.string.settings_clear_message)
                .setPositiveButton(R.string.settings_clear_ok) { _, _ ->
                    memory.clearMemory()
                    history = mutableListOf()
                    expectingName = false
                    nameAskedThisSession = false
                    memoryInfo.text = getString(R.string.settings_memory_info, 0)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        testModelButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val modelo = modelInput.text.toString().trim().ifBlank { appConfig.model }
            if (key.isEmpty()) {
                keyInput.error = getString(R.string.settings_key_required)
                return@setOnClickListener
            }
            testResultDePrueba(getString(R.string.settings_testing), corto = true)
            lifecycleScope.launch {
                val prueba = withContext(Dispatchers.IO) {
                    runCatching {
                        SamanthaApi(
                            appConfig.baseUrl,
                            key,
                            modelo,
                            sessionId(),
                            SamanthaApi.loadPersona(applicationContext),
                            appConfig.reasoningEffort,
                            appConfig.maxTokens,
                            appConfig.temperature,
                            appConfig.firecrawlApiKey,
                            appConfig.firecrawlMaxResults,
                            appConfig.firecrawlLang,
                            appConfig.firecrawlCountry,
                            appConfig.firecrawlTimeoutMs,
                            appConfig.historyLimit,
                        ).chat(
                            emptyList(),
                            if (languageMode == "en") "Introduce yourself in one sentence."
                            else "Preséntate en una frase.",
                            memory.userName,
                            memory.facts(),
                            false,
                            languageMode,
                        )
                    }
                }
                testResultDePrueba(
                    prueba.fold(
                        onSuccess = { getString(R.string.settings_test_ok, it.take(180)) },
                        onFailure = { getString(R.string.settings_test_fail, llmError(it)) }
                    )
                )
            }
        }

        voiceButton.setOnClickListener {
            val idioma = if (languageGroup.checkedButtonId == R.id.languageEn) "en" else "es"
            elegirVoz(idioma)
        }

        cancelButton.setOnClickListener { dialogo.dismiss() }

        fun aplicarAjustes() {
            val key = keyInput.text.toString().trim()
            val modelo = modelInput.text.toString().trim().ifBlank { appConfig.model }
            val nuevoIdioma =
                if (languageGroup.checkedButtonId == R.id.languageEn) "en" else "es"
            val cambioIdioma = nuevoIdioma != languageMode
            val nuevoWake = wakeSwitch.isChecked
            val nuevoBackground =
                wakeModeGroup.checkedButtonId == R.id.wakeModeBackground

            autoListen = autoSwitch.isChecked
            if (!autoListen) {
                binding.root.removeCallbacks(autoListenRunnable)
            }
            animatedBackground = when (backgroundGroup.checkedButtonId) {
                R.id.backgroundLow -> "low"
                R.id.backgroundOff -> "off"
                else -> "on"
            }
            keepScreenOn = keepScreenSwitch.isChecked
            wakeWordEnabled = nuevoWake
            wakeWordBackground = nuevoBackground
            prefs.edit()
                .putString(KEY_API, key)
                .putString(KEY_MODEL, modelo)
                .putBoolean(KEY_AUTO_LISTEN, autoListen)
                .putString(KEY_LANGUAGE, nuevoIdioma)
                .putString(KEY_ANIMATED_BG, animatedBackground)
                .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
                .putBoolean(WakeWordSettings.KEY_ENABLED, wakeWordEnabled)
                .putBoolean(WakeWordSettings.KEY_BACKGROUND, wakeWordBackground)
                .apply()
            binding.backdrop.setMode(animatedBackground)
            aplicarPantallaEncendida()
            if (cambioIdioma) {
                languageMode = nuevoIdioma
                reiniciarTts()
            }
            updateUi(estadoReposo())
            dialogo.dismiss()
            if (!wakeWordEnabled) {
                wakeWordEngine?.detener()
                wakeWordEngine?.liberar()
                pararServicioWakeWord()
                binding.root.postDelayed({
                    if (!wakeWordEnabled) VoskModelHolder.liberar()
                }, 1500L)
            } else {
                if (wakeWordBackground) {
                    iniciarServicioWakeWord()
                    accionServicioWakeWord(WakeWordService.ACTION_PAUSE)
                    pedirPermisoNotificaciones()
                } else {
                    pararServicioWakeWord()
                }
                syncWakeWord()
            }
        }

        saveButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) {
                keyInput.error = getString(R.string.settings_key_required)
                return@setOnClickListener
            }
            if (wakeSwitch.isChecked && !VoskModelStore.listo(this)) {
                descargarModelo { ok ->
                    if (ok) {
                        actualizarEstadoModelo(wakeModelStatus)
                        aplicarAjustes()
                    }
                }
            } else {
                aplicarAjustes()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(vista) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                bars.left + dp(8),
                bars.top + dp(8),
                bars.right + dp(8),
                bars.bottom + dp(8)
            )
            insets
        }
        dialogo.window?.let { pantallaCompleta(it) }
        dialogo.show()
        dialogo.window?.let {
            pantallaCompleta(it)
            it.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    private fun configurado(): Boolean =
        apiKey().isNotBlank() && appConfig.model.isNotBlank()

    private fun estadoReposo(): OrbView.State =
        if (configurado()) OrbView.State.READY else OrbView.State.WAITING

    private fun enReposo(state: OrbView.State): Boolean =
        state == OrbView.State.WAITING || state == OrbView.State.READY

    private fun testResultDePrueba(mensaje: String, corto: Boolean = false) {
        Toast.makeText(
            this,
            mensaje,
            if (corto) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
    }

    private fun sessionId(): String =
        prefs.getString(KEY_SESSION, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_SESSION, it).apply()
            }

    // ── UI ────────────────────────────────────────────────────────────

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.error_no_match)

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.error_network)

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.error_busy)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.error_permissions)
        SpeechRecognizer.ERROR_SERVER -> getString(R.string.error_server)
        else -> getString(R.string.error_generic, error)
    }

    private fun updateUi(state: OrbView.State) {
        binding.orb.state = state
        binding.orb.contentDescription =
            if (state == OrbView.State.LISTENING || state == OrbView.State.HEARING) {
                getString(R.string.button_stop)
            } else {
                getString(R.string.button_listen)
            }
        binding.backdrop.state = state
        syncWakeWord()
    }

    private fun showError(message: String) {
        binding.orb.setLevel(0f)
        binding.backdrop.setLevel(0f)
        updateUi(OrbView.State.ERROR)
        // Sin etiqueta de estado: el error se muestra en la tarjeta.
        mostrarRespuesta(message, R.color.red)
    }

    /** Pinta la tarjeta de respuesta y devuelve el scroll al inicio. */
    private fun mostrarRespuesta(texto: String, colorRes: Int) {
        binding.response.text = texto
        binding.response.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.responseScroll.visibility = View.VISIBLE
        binding.responseScroll.scrollTo(0, 0)
    }


    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // El fondo va de borde a borde; el padding se aplica al contenido
            // para que el orbe y los botones no queden bajo el cutout.
            binding.contentScroll.setPadding(
                bars.left + dp(8),
                bars.top + dp(8),
                bars.right + dp(8),
                bars.bottom + dp(16)
            )
            val parametros =
                binding.settingsButton.layoutParams as FrameLayout.LayoutParams
            parametros.topMargin = bars.top + dp(2)
            parametros.marginEnd = bars.right + dp(6)
            binding.settingsButton.layoutParams = parametros
            insets
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /** Oculta las barras del sistema; se muestran solo con un deslizamiento. */
    private fun pantallaCompleta(ventana: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ventana.attributes = ventana.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(ventana, false)
        WindowInsetsControllerCompat(ventana, ventana.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WakeWordSettings.EXTRA_WAKE_WORD, false)) {
            wakeLaunch = true
            binding.root.removeCallbacks(wakeWordRunnable)
            wakeWordEngine?.detener()
            binding.root.postDelayed({ lanzarDesdeWakeWord() }, 700L)
        }
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        aplicarPantallaEncendida()
        binding.backdrop.resume()
        binding.root.post { sincronizarFocoFondo() }
        WakeWordSettings.setAppVisible(this, true)
        getSystemService(NotificationManager::class.java)
            ?.cancel(WakeWordService.ALERT_ID)
        pantallaCompleta(window)
        if (wakeWordEnabled && wakeWordBackground) {
            // El servicio se inicia con la app visible; Android 14+ no deja
            // arrancar un servicio de micrófono desde segundo plano.
            iniciarServicioWakeWord()
            accionServicioWakeWord(WakeWordService.ACTION_PAUSE)
        } else {
            pararServicioWakeWord()
        }
        syncWakeWord(inmediato = true)
        actualizarPermisoAutoOpen()
    }

    private fun aplicarPantallaEncendida() {
        if (keepScreenOn && enPrimerPlano) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun sincronizarFocoFondo() {
        val orb = binding.orb
        val fondo = binding.backdrop
        if (orb.width == 0 || orb.height == 0) return
        val posOrbe = IntArray(2)
        val posFondo = IntArray(2)
        orb.getLocationInWindow(posOrbe)
        fondo.getLocationInWindow(posFondo)
        fondo.setFocus(
            posOrbe[0] - posFondo[0] + orb.width / 2f,
            posOrbe[1] - posFondo[1] + orb.height / 2f,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && settingsDialog?.isShowing != true) {
            pantallaCompleta(window)
        }
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
        aplicarPantallaEncendida()
        binding.backdrop.pause()
        WakeWordSettings.setAppVisible(this, false)
        binding.root.removeCallbacks(autoListenRunnable)
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        autoTurn = false
        tts?.stop()
        if (binding.orb.state == OrbView.State.SPEAKING) {
            updateUi(estadoReposo())
        }
        previewTts?.stop()
        if (listening) {
            stopping = true
            listening = false
            speech?.cancel()
            updateUi(estadoReposo())
        }
        if (wakeWordEnabled && wakeWordBackground) {
            accionServicioWakeWord(WakeWordService.ACTION_RESUME)
            // Si el servicio aún se estaba creando, reintenta el aviso.
            binding.root.postDelayed({
                if (!enPrimerPlano && wakeWordEnabled && wakeWordBackground) {
                    accionServicioWakeWord(WakeWordService.ACTION_RESUME)
                }
            }, 900L)
        } else {
            pararServicioWakeWord()
        }
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(autoListenRunnable)
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        wakeWordEngine = null
        if (!(wakeWordEnabled && wakeWordBackground)) pararServicioWakeWord()
        tts?.stop()
        tts?.shutdown()
        tts = null
        previewTts?.stop()
        previewTts?.shutdown()
        previewTts = null
        speech?.destroy()
        speech = null
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "samantha"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SESSION = "session_id"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_LANGUAGE = "language_mode"
        private const val KEY_ANIMATED_BG = "animated_background"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        /** Margen después del TTS para que no se cuele la cola de la voz. */
        private const val AUTO_LISTEN_DELAY_MS = 200L

        /** Margen extra cuando la voz es de red y el audio llega tras onDone. */
        private const val AUTO_LISTEN_NETWORK_GUARD_MS = 400L

        /** Si el TTS sigue sonando, se reintenta antes de abrir el micrófono. */
        private const val AUTO_LISTEN_TTS_RETRY_MS = 250L
        private const val AUTO_LISTEN_TTS_MAX_RETRIES = 20

        /** Espera antes de reabrir Vosk; cubre el eco del TTS y el micrófono. */
        private const val WAKE_WORD_DELAY_MS = 1400L

        /** Al abrir la app no hay TTS: Vosk puede arrancar casi de inmediato. */
        private const val WAKE_WORD_STARTUP_DELAY_MS = 300L
    }
}
