package com.cdcb.samanthapp

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.cdcb.samanthapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    private lateinit var speechController: SpeechController
    private var listening = false
    internal var history = mutableListOf<Message>()
    internal var expectingName = false
    internal var nameAskedThisSession = false
    internal lateinit var memory: MemoryStore
    internal lateinit var appConfig: SamanthaConfigData
    internal var autoListen = false
    private var autoListenTtsRetries = 0
    private var errorResetRunnable: Runnable? = null
    internal var languageMode = "es"

    internal var wakeWordEnabled = false
    internal var wakeWordBackground = false
    internal var wakeWordEngine: WakeWordEngine? = null
    private var wakeLaunch = false
    private var enPrimerPlano = false
    internal lateinit var ttsController: TtsController
    internal lateinit var oled: OledProtectionController
    internal lateinit var layoutController: OrbLayoutController
    private lateinit var settingsController: SettingsDialogController
    internal val wakeWordRunnable = Runnable { iniciarWakeWordSiPuede() }

    internal val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                showError(getString(R.string.permission_needed))
            }
        }

    internal val notificationPermissionLauncher =
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
        oled = OledProtectionController(
            this,
            binding.root,
            binding.settingsButton,
            binding.orb,
        ) { settingsController.isShowing }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pantallaCompleta(window)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        layoutController = OrbLayoutController(this, binding)
        layoutController.install()
        layoutController.applyOrientation(resources.configuration.orientation)

        appConfig = SamanthaConfig.load(this)
        memory = MemoryStore(this, appConfig.historyLimit)
        history = memory.history()
        autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, false)
        languageMode = prefs.getString(KEY_LANGUAGE, "es")
            ?.takeIf { it == "en" } ?: "es"
        Log.i("Samantha", "razonamiento: ${reasoningEffortActual()}")
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

        speechController = SpeechController(
            context = this,
            languageMode = { languageMode },
            onReady = { onSpeechReady() },
            onRms = { onSpeechRms(it) },
            onEndVoice = { onSpeechEnd() },
            onError = { error, cancelled, automatic ->
                onSpeechError(error, cancelled, automatic)
            },
            onResults = { onSpeechResults(it) },
            onPartialResults = { onSpeechPartial(it) },
        )

        binding.orb.setOnClickListener { toggleListening() }
        binding.settingsButton.setOnClickListener { settingsController.open() }
        binding.backdrop.setMode("on")
        updateUi(estadoReposo())

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.recognition_unavailable))
        }

        ttsController = TtsController(
            activity = this,
            prefs = prefs,
            response = binding.response,
            responseScroll = binding.responseScroll,
            languageMode = { languageMode },
            onSpeaking = { updateUi(OrbView.State.SPEAKING) },
            onFinished = {
                Log.i("Samantha", "TTS terminó; auto-listen=$autoListen")
                scheduleAutoListen()
                updateUi(estadoReposo())
            },
            onNotReady = { updateUi(estadoReposo()) },
            onResponseShown = { layoutController.rebalance() },
            onPreviewStarting = {
                if (!enReposo(binding.orb.state)) updateUi(estadoReposo())
                binding.root.removeCallbacks(autoListenRunnable)
            },
        )
        settingsController = SettingsDialogController(this, binding)
        if (wakeLaunch) {
            binding.root.postDelayed({ lanzarDesdeWakeWord() }, 800L)
        }
        // Primer arranque sin API key: abre Ajustes para configurarla.
        if (apiKey().isBlank()) {
            binding.root.postDelayed({
                if (!isFinishing && apiKey().isBlank() &&
                    !settingsController.isShowing
                ) {
                    settingsController.open()
                }
            }, 600L)
        }
    }

    internal val autoListenRunnable = Runnable { maybeAutoListen() }

    private fun scheduleAutoListen() {
        if (!autoListen) return
        autoListenTtsRetries = 0
        binding.root.removeCallbacks(autoListenRunnable)
        // 200 ms de margen para que no se cuele la cola del TTS. Si la voz
        // es de red, el audio puede llegar después del onDone: se añade
        // margen extra para no oír la propia respuesta en el micrófono.
        val extraRed = if (ttsController.usesNetworkVoice()) {
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
        if (ttsController.isSpeaking()) {
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
    internal fun syncWakeWord(inmediato: Boolean = false) {
        binding.root.removeCallbacks(wakeWordRunnable)
        if (!wakeWordEnabled || wakeLaunch || !enPrimerPlano) {
            wakeWordEngine?.detener()
            return
        }
        val libre = enReposo(binding.orb.state) &&
            !listening && !settingsController.isShowing
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
        if (settingsController.isShowing) return
        Log.i("Samantha", "wake word: inicio de la escucha")
        wakeWordEngine?.iniciar()
    }

    private fun alDetectarWakeWord() {
        if (isFinishing) return
        oled.onUserActivity()
        if (listening || !enPrimerPlano) return
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

    internal fun iniciarServicioWakeWord() {
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

    internal fun pararServicioWakeWord() {
        stopService(Intent(this, WakeWordService::class.java))
    }

    internal fun accionServicioWakeWord(accion: String) {
        if (!WakeWordService.running) return
        try {
            startService(
                Intent(this, WakeWordService::class.java).setAction(accion)
            )
        } catch (e: Exception) {
            Log.w("Samantha", "acción del servicio de wake word: ${e.message}")
        }
    }

    internal fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── reconocimiento ────────────────────────────────────────────────

    private fun toggleListening() {
        binding.root.removeCallbacks(autoListenRunnable)
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        binding.orb.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.backdrop.ripple()

        if (listening) {
            listening = false
            speechController.stopListening()
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
            ttsController.stop()
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
        cancelarReposoTrasError()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.recognition_unavailable))
            return
        }
        if (apiKey().isBlank()) {
            showError(getString(R.string.settings_key_required))
            settingsController.open()
            return
        }

        ttsController.stop()
        ttsController.cancelReveal()
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        listening = true
        binding.responseScroll.visibility = View.GONE
        binding.transcript.visibility = View.GONE
        layoutController.rebalance()
        updateUi(OrbView.State.LISTENING)
        speechController.start(automatic)
    }

    // ── reconocimiento de voz ─────────────────────────────────────────

    private fun onSpeechReady() {
        listening = true
        updateUi(OrbView.State.LISTENING)
    }

    private fun onSpeechRms(rmsdB: Float) {
        binding.orb.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        binding.backdrop.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        if (rmsdB > 3f && binding.orb.state == OrbView.State.LISTENING) {
            binding.orb.state = OrbView.State.HEARING
        }
    }

    private fun onSpeechEnd() {
        listening = false
        updateUi(OrbView.State.TRANSCRIBING)
    }

    private fun onSpeechError(error: Int, cancelled: Boolean, automatic: Boolean) {
        listening = false
        binding.orb.setLevel(0f)
        if (cancelled) {
            updateUi(estadoReposo())
        } else if (
            automatic &&
            (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        ) {
            // Una sola ventana: si no hablas, vuelve a esperar un toque
            // o a la palabra de activación, sin abrir otra ventana.
            Log.i("Samantha", "auto-listen: sin voz, vuelvo a esperar")
            updateUi(estadoReposo())
        } else {
            val sinVoz = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            showError(errorMessage(error))
            if (sinVoz) programarReposoTrasError()
        }
    }

    private fun onSpeechResults(texto: String) {
        listening = false
        binding.orb.setLevel(0f)
        if (texto.isEmpty()) {
            showError(getString(R.string.error_no_match))
            return
        }
        binding.transcript.text = texto
        binding.transcript.visibility = View.VISIBLE
        layoutController.rebalance()
        updateUi(OrbView.State.THINKING)
        handleMemoryOrModel(texto)
    }

    private fun onSpeechPartial(texto: String) {
        if (texto.isNotEmpty()) {
            binding.transcript.text = texto
            binding.transcript.visibility = View.VISIBLE
            layoutController.rebalance()
        }
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
        ttsController.showResponse(respuesta, R.color.cyan)
        ttsController.speak(respuesta)
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
                        reasoningEffortActual(),
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
                ttsController.showResponse(respuesta, R.color.cyan)
                ttsController.speak(respuesta)
            }.onFailure { e ->
                showError(llmError(e))
            }
        }
    }

    internal fun llmError(e: Throwable): String = when {
        e is SamanthaApi.ApiException && e.code in 401..403 ->
            getString(R.string.error_auth)
        e is UnknownHostException || e is ConnectException ->
            getString(R.string.error_network)
        e is SocketTimeoutException -> getString(R.string.error_timeout)
        else -> getString(R.string.error_generic_llm, e.message?.take(80) ?: "")
    }

    // ── ajustes ───────────────────────────────────────────────────────

    internal fun actualizarEstadoModelo(view: TextView) {
        view.text = if (VoskModelStore.listo(this)) {
            getString(R.string.settings_wake_model_ready)
        } else {
            getString(R.string.settings_wake_model_missing)
        }
    }

    internal fun descargarModelo(onDone: (Boolean) -> Unit) {
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

    internal fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    internal fun reasoningEffortActual(): String {
        val guardado = prefs.getString(KEY_REASONING, null)?.lowercase()
        if (guardado != null && guardado in NIVELES_RAZONAMIENTO) return guardado
        val configurado = appConfig.reasoningEffort.lowercase()
        return if (configurado in NIVELES_RAZONAMIENTO) configurado else "high"
    }

    internal fun configurado(): Boolean =
        apiKey().isNotBlank() && appConfig.model.isNotBlank()

    internal fun estadoReposo(): OrbView.State =
        if (configurado()) OrbView.State.READY else OrbView.State.WAITING

    internal fun enReposo(state: OrbView.State): Boolean =
        state == OrbView.State.WAITING || state == OrbView.State.READY

    internal fun testResultDePrueba(mensaje: String, corto: Boolean = false) {
        Toast.makeText(
            this,
            mensaje,
            if (corto) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
    }

    internal fun sessionId(): String =
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

    internal fun updateUi(state: OrbView.State) {
        binding.orb.state = state
        binding.orb.contentDescription =
            if (state == OrbView.State.LISTENING || state == OrbView.State.HEARING) {
                getString(R.string.button_stop)
            } else {
                getString(R.string.button_listen)
            }
        binding.backdrop.state = state
        oled.onStateChange()
        syncWakeWord()
    }

    private fun showError(message: String) {
        cancelarReposoTrasError()
        binding.orb.setLevel(0f)
        binding.backdrop.setLevel(0f)
        updateUi(OrbView.State.ERROR)
        // Sin etiqueta de estado: el error se muestra en la tarjeta.
        ttsController.showResponse(message, R.color.red, revealWithVoice = false)
    }

    /**
     * Cuando no se oye voz, el rojo no debe quedarse fijo: tras unos segundos
     * vuelve al reposo (azul) y se retira el aviso.
     */
    private fun programarReposoTrasError() {
        cancelarReposoTrasError()
        val runnable = Runnable {
            errorResetRunnable = null
            if (binding.orb.state != OrbView.State.ERROR) return@Runnable
            Log.i("Samantha", "sin voz: vuelvo a reposo")
            binding.orb.setLevel(0f)
            binding.backdrop.setLevel(0f)
            binding.responseScroll.visibility = View.GONE
            binding.transcript.visibility = View.GONE
            updateUi(estadoReposo())
            layoutController.rebalance()
        }
        errorResetRunnable = runnable
        binding.root.postDelayed(runnable, ERROR_NO_SPEECH_RESET_MS)
    }

    private fun cancelarReposoTrasError() {
        errorResetRunnable?.let { binding.root.removeCallbacks(it) }
        errorResetRunnable = null
    }

    internal fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /** Oculta las barras del sistema; se muestran solo con un deslizamiento. */
    internal fun pantallaCompleta(ventana: Window) {
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
        layoutController.syncFocus()
        oled.onResume()
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
        settingsController.refreshAutoOpen()
    }

    private fun aplicarPantallaEncendida() {
        if (enPrimerPlano) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Protección OLED: oculta los tres puntos, atenúa la ventana y desplaza el
     * orbe para que ningún píxel reciba siempre el mismo brillo. Cualquier
     * toque o la wake word llaman a [oled] y restauran todo.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) oled.onUserActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutController.applyOrientation(newConfig.orientation)
        pantallaCompleta(window)
        binding.root.requestApplyInsets()
        layoutController.syncFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !settingsController.isShowing) {
            pantallaCompleta(window)
        }
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
        oled.onPause()
        aplicarPantallaEncendida()
        binding.backdrop.pause()
        WakeWordSettings.setAppVisible(this, false)
        binding.root.removeCallbacks(autoListenRunnable)
        binding.root.removeCallbacks(wakeWordRunnable)
        wakeWordEngine?.detener()
        ttsController.pause()
        if (binding.orb.state == OrbView.State.SPEAKING) {
            updateUi(estadoReposo())
        }
        if (listening) {
            listening = false
            speechController.cancel()
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
        cancelarReposoTrasError()
        oled.onDestroy()
        wakeWordEngine?.detener()
        wakeWordEngine = null
        if (!(wakeWordEnabled && wakeWordBackground)) pararServicioWakeWord()
        ttsController.shutdown()
        speechController.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "samantha"
        internal const val KEY_API = "api_key"
        internal const val KEY_MODEL = "model"
        internal const val KEY_SESSION = "session_id"
        internal const val KEY_AUTO_LISTEN = "auto_listen"
        internal const val KEY_REASONING = "reasoning_effort"
        internal const val KEY_LANGUAGE = "language_mode"

        /** Margen después del TTS para que no se cuele la cola de la voz. */
        private const val AUTO_LISTEN_DELAY_MS = 200L

        /** Tiempo que el orbe permanece rojo cuando no se oye voz. */
        private const val ERROR_NO_SPEECH_RESET_MS = 3500L

        /** Niveles de razonamiento que acepta el backend. */
        internal val NIVELES_RAZONAMIENTO =
            arrayOf("low", "medium", "high", "xhigh", "max")

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
