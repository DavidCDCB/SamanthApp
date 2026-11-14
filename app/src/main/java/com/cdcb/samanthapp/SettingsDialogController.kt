package com.cdcb.samanthapp

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cdcb.samanthapp.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Diálogo de Ajustes. Mantiene su propio estado y delega la lógica de
 * voz, wake word, memoria y modelo en MainActivity. */
class SettingsDialogController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {

    private var dialog: Dialog? = null
    private var autoOpenStatusView: TextView? = null
    private var autoOpenButtonView: MaterialButton? = null
    private var wakeModeGroupView: MaterialButtonToggleGroup? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun refreshAutoOpen() = with(activity) { actualizarPermisoAutoOpen() }

    fun open() {
        with(activity) {
            if (isShowing) return
            binding.root.removeCallbacks(wakeWordRunnable)
            wakeWordEngine?.detener()
            val vista = layoutInflater.inflate(R.layout.dialog_settings, null)
            var guardado = false
            val rateAntes = ttsController.speechRate
            val keyInput = vista.findViewById<EditText>(R.id.keyInput)
            val modelInput = vista.findViewById<EditText>(R.id.modelInput)
            val reasoningInput =
                vista.findViewById<MaterialAutoCompleteTextView>(R.id.reasoningInput)
            val testModelButton = vista.findViewById<MaterialButton>(R.id.testModelButton)
            val saveButton = vista.findViewById<MaterialButton>(R.id.saveButton)
            val cancelButton = vista.findViewById<MaterialButton>(R.id.cancelButton)
            val memoryInfo = vista.findViewById<TextView>(R.id.memoryInfo)
            val clearButton = vista.findViewById<MaterialButton>(R.id.clearMemoryButton)
            val voiceButton = vista.findViewById<MaterialButton>(R.id.voiceButton)
            val autoSwitch = vista.findViewById<MaterialSwitch>(R.id.autoListenSwitch)
            val ttsRateSlider = vista.findViewById<Slider>(R.id.ttsRateSlider)
            val ttsRateValue = vista.findViewById<TextView>(R.id.ttsRateValue)
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
                prefs.getString(MainActivity.KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: appConfig.model
            )
            val adaptadorRazonamiento = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                MainActivity.NIVELES_RAZONAMIENTO,
            )
            reasoningInput.setAdapter(adaptadorRazonamiento)
            var razonamientoSeleccionado = reasoningEffortActual()
            reasoningInput.setText(razonamientoSeleccionado, false)
            reasoningInput.setOnItemClickListener { _, _, position, _ ->
                razonamientoSeleccionado = MainActivity.NIVELES_RAZONAMIENTO[position]
            }
            reasoningInput.setOnClickListener { reasoningInput.showDropDown() }
            memoryInfo.text = getString(R.string.settings_memory_info, memory.facts().size)
            Log.i("Samantha", "ajustes: ${memory.facts().size} recuerdos")
            autoSwitch.isChecked = autoListen
            ttsRateSlider.value = ttsController.speechRate.coerceIn(0.5f, 2.0f)
            ttsRateValue.text = String.format(Locale("es", "CO"), "%.2f×", ttsRateSlider.value)
            ttsRateSlider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                ttsController.updateSpeechRate(value)
                ttsRateValue.text = String.format(Locale("es", "CO"), "%.2f×", value)
                Log.i("Samantha", "velocidad TTS: $value")
            }
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
            dialog = dialogo
            dialogo.setOnDismissListener {
                dialog = null
                autoOpenStatusView = null
                autoOpenButtonView = null
                wakeModeGroupView = null
                if (!guardado) {
                    ttsController.updateSpeechRate(rateAntes)
                }
                ttsController.stopPreview()
                pantallaCompleta(window)
                syncWakeWord()
                oled.onUserActivity()
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
                                razonamientoSeleccionado,
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
                ttsController.chooseVoice(idioma)
            }

            cancelButton.setOnClickListener { dialogo.dismiss() }

            fun aplicarAjustes() {
                guardado = true
                val key = keyInput.text.toString().trim()
                val modelo = modelInput.text.toString().trim().ifBlank { appConfig.model }
                val razonamiento = reasoningInput.text.toString().lowercase()
                    .takeIf { it in MainActivity.NIVELES_RAZONAMIENTO } ?: razonamientoSeleccionado
                razonamientoSeleccionado = razonamiento
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
                wakeWordEnabled = nuevoWake
                wakeWordBackground = nuevoBackground
                prefs.edit()
                    .putString(MainActivity.KEY_API, key)
                    .putString(MainActivity.KEY_MODEL, modelo)
                    .putString(MainActivity.KEY_REASONING, razonamiento)
                    .putBoolean(MainActivity.KEY_AUTO_LISTEN, autoListen)
                    .putString(MainActivity.KEY_LANGUAGE, nuevoIdioma)
                    .putBoolean(WakeWordSettings.KEY_ENABLED, wakeWordEnabled)
                    .putBoolean(WakeWordSettings.KEY_BACKGROUND, wakeWordBackground)
                    .apply()
                ttsController.persistSpeechRate()
                if (cambioIdioma) {
                    languageMode = nuevoIdioma
                    ttsController.restart()
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
    }

    private fun actualizarPermisoAutoOpen() {
        val status = autoOpenStatusView ?: return
        val button = autoOpenButtonView ?: return
        val permitido = Settings.canDrawOverlays(activity)
        status.visibility = View.VISIBLE
        button.visibility = View.VISIBLE
        status.text = activity.getString(
            if (permitido) R.string.settings_wake_auto_open_granted
            else R.string.settings_wake_auto_open_missing
        )
        button.text = activity.getString(
            if (permitido) R.string.settings_wake_auto_open_button_granted
            else R.string.settings_wake_auto_open_button
        )
        button.isEnabled = !permitido
    }
}
