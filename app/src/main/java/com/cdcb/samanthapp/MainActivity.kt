package com.cdcb.samanthapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Dialog
import android.os.Bundle
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
import android.widget.EditText
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
import com.cdcb.samanthapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var greetingJob: Job? = null
    private var pendingSpeech: String? = null
    private lateinit var memory: MemoryStore
    private lateinit var appConfig: SamanthaConfigData
    private var autoListen = false
    private var autoTurn = false
    private var settingsDialog: Dialog? = null
    private var previewTts: TextToSpeech? = null
    private var languageMode = "es"

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                showError(getString(R.string.permission_needed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        applyInsets()

        memory = MemoryStore(this)
        history = memory.history()
        appConfig = SamanthaConfig.load(this)
        autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, false)
        languageMode = prefs.getString(KEY_LANGUAGE, "es")
            ?.takeIf { it == "en" } ?: "es"

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(recognitionListener)
        speech = recognizer

        binding.orb.setOnClickListener { toggleListening() }
        binding.settingsButton.setOnClickListener { openSettings() }
        updateUi(estadoReposo())

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.recognition_unavailable))
        }

        initTts()
        scheduleGreeting()
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

    // ── saludo de apertura ────────────────────────────────────────────

    /**
     * Al abrir la app (con key configurada) inicia una conversación sola,
     * como el asistente de PC: saluda, pregunta el nombre si no lo sabe y el
     * prompt ya lleva la fecha y la hora de este momento.
     */
    private fun scheduleGreeting() {
        if (apiKey().isBlank()) return
        greetingJob = lifecycleScope.launch {
            delay(1400)
            if (listening || !enReposo(binding.orb.state)) return@launch
            startGreeting()
        }
    }

    private fun startGreeting() {
        if (apiKey().isBlank()) return
        val saludo = getString(
            if (languageMode == "en") R.string.greeting_text_en else R.string.greeting_text
        )
        binding.transcript.text = saludo
        binding.transcript.visibility = View.VISIBLE
        binding.responseScroll.visibility = View.GONE
        updateUi(OrbView.State.THINKING)
        askModel(saludo)
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
                    runOnUiThread { finishSpeaking() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { finishSpeaking() }
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

    private fun speak(texto: String) {        val motor = tts
        if (!ttsReady || motor == null) {
            // El TTS puede tardar en inicializar; se dice en cuanto esté listo.
            pendingSpeech = texto
            updateUi(estadoReposo())
            return
        }
        updateUi(OrbView.State.SPEAKING)
        motor.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "samantha-${System.currentTimeMillis()}")
    }

    private fun finishSpeaking() {
        updateUi(estadoReposo())
        scheduleAutoListen()
    }

    private val autoListenRunnable = Runnable { maybeAutoListen() }

    private fun scheduleAutoListen() {
        if (!autoListen) return
        binding.root.removeCallbacks(autoListenRunnable)
        // Pequeña espera anti-eco después de que termina el audio.
        binding.root.postDelayed(autoListenRunnable, 700L)
    }

    private fun maybeAutoListen() {
        if (!autoListen || listening || isFinishing) return
        if (!enReposo(binding.orb.state)) return
        val permiso = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )
        if (permiso != PackageManager.PERMISSION_GRANTED) return
        Log.i("Samantha", "auto-listen: reabro el micrófono")
        startListening(automatic = true)
    }

    // ── reconocimiento ────────────────────────────────────────────────

    private fun toggleListening() {
        greetingJob?.cancel()
        greetingJob = null
        binding.root.removeCallbacks(autoListenRunnable)
        binding.orb.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (listening) {
            stopping = true
            listening = false
            speech?.stopListening()
            updateUi(OrbView.State.TRANSCRIBING)
            return
        }

        // Mientras piensa o transcribe no se abre otro turno; al hablar, el
        // toque corta la voz y empieza a escuchar (barge-in).
        when (binding.orb.state) {
            OrbView.State.THINKING,
            OrbView.State.TRANSCRIBING -> return
            else -> Unit
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
                // Tras una respuesta, si no hablas, no llenamos la pantalla
                // de errores: se queda esperando otro toque.
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
        if (history.size > 8) history = history.takeLast(8).toMutableList()
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
                if (history.size > 8) history = history.takeLast(8).toMutableList()
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

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return
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
        val languageGroup = vista.findViewById<MaterialButtonToggleGroup>(R.id.languageGroup)
        val closeButton = vista.findViewById<ImageButton>(R.id.closeSettings)

        keyInput.setText(apiKey())
        modelInput.setText(
            prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: appConfig.model
        )
        memoryInfo.text = getString(R.string.settings_memory_info, memory.facts().size)
        Log.i("Samantha", "ajustes: ${memory.facts().size} recuerdos")
        autoSwitch.isChecked = autoListen
        languageGroup.check(if (languageMode == "en") R.id.languageEn else R.id.languageEs)

        val dialogo = Dialog(this, R.style.Theme_SamanthApp_FullScreenDialog)
        dialogo.setContentView(vista)
        dialogo.setCanceledOnTouchOutside(false)
        settingsDialog = dialogo
        dialogo.setOnDismissListener {
            settingsDialog = null
            previewTts?.stop()
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

        saveButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val modelo = modelInput.text.toString().trim().ifBlank { appConfig.model }
            if (key.isEmpty()) {
                keyInput.error = getString(R.string.settings_key_required)
                return@setOnClickListener
            }
            val nuevoIdioma =
                if (languageGroup.checkedButtonId == R.id.languageEn) "en" else "es"
            val cambioIdioma = nuevoIdioma != languageMode
            autoListen = autoSwitch.isChecked
            if (!autoListen) binding.root.removeCallbacks(autoListenRunnable)
            prefs.edit()
                .putString(KEY_API, key)
                .putString(KEY_MODEL, modelo)
                .putBoolean(KEY_AUTO_LISTEN, autoListen)
                .putString(KEY_LANGUAGE, nuevoIdioma)
                .apply()
            if (cambioIdioma) {
                languageMode = nuevoIdioma
                reiniciarTts()
            }
            updateUi(estadoReposo())
            dialogo.dismiss()
        }

        ViewCompat.setOnApplyWindowInsetsListener(vista) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                bars.left + dp(8),
                bars.top + dp(4),
                bars.right + dp(8),
                bars.bottom + dp(8)
            )
            insets
        }
        dialogo.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        dialogo.show()
        dialogo.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
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
    }

    private fun showError(message: String) {
        binding.orb.setLevel(0f)
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                bars.left + dp(8),
                bars.top + dp(8),
                bars.right + dp(8),
                bars.bottom + dp(16)
            )
            insets
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onPause() {
        super.onPause()
        greetingJob?.cancel()
        greetingJob = null
        binding.root.removeCallbacks(autoListenRunnable)
        autoTurn = false
        tts?.stop()
        previewTts?.stop()
        if (listening) {
            stopping = true
            listening = false
            speech?.cancel()
            updateUi(estadoReposo())
        }
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(autoListenRunnable)
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
    }
}
