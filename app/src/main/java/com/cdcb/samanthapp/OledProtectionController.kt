package com.cdcb.samanthapp

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlin.random.Random

/**
 * Protección contra burn-in del AMOLED.
 *
 * - A los 30 s oculta el botón de Ajustes.
 * - A los 3 min atenúa la ventana al 18 %.
 * - Tras 1 h sin uso baja al 5 %.
 * - Cada 2 min desplaza el orbe unos píxeles.
 *
 * Cualquier toque, cambio de estado o wake word llama a [onUserActivity]
 * y restaura el brillo y el botón.
 */
class OledProtectionController(
    private val activity: MainActivity,
    private val root: View,
    private val settingsButton: View,
    private val orb: OrbView,
    private val isSettingsOpen: () -> Boolean,
) {

    private var foreground = false
    private var screenDimmed = false
    private var screenVeryDimmed = false

    private val dimRunnable = Runnable { dimScreen() }
    private val longDimRunnable = Runnable { dimMore() }
    private val hideSettingsRunnable = Runnable { hideSettingsButton() }
    private val driftRunnable = object : Runnable {
        override fun run() {
            if (!foreground) return
            var dx = Random.nextInt(-2, 3)
            var dy = Random.nextInt(-2, 3)
            if (dx == 0 && dy == 0) dx = 1
            val density = activity.resources.displayMetrics.density
            orb.setDrift(dx * density, dy * density)
            Log.i("Samantha", "OLED: deriva del orbe dx=$dx dy=$dy")
            root.postDelayed(this, ORB_DRIFT_INTERVAL_MS)
        }
    }

    fun onResume() {
        foreground = true
        onUserActivity()
        root.removeCallbacks(driftRunnable)
        root.postDelayed(driftRunnable, ORB_DRIFT_INTERVAL_MS)
    }

    fun onPause() {
        foreground = false
        root.removeCallbacks(dimRunnable)
        root.removeCallbacks(longDimRunnable)
        root.removeCallbacks(hideSettingsRunnable)
        root.removeCallbacks(driftRunnable)
        restoreBrightness()
        showSettingsButton()
    }

    fun onDestroy() {
        onPause()
    }

    fun onUserActivity() {
        if (!foreground) return
        root.removeCallbacks(dimRunnable)
        root.removeCallbacks(longDimRunnable)
        root.removeCallbacks(hideSettingsRunnable)
        restoreBrightness()
        showSettingsButton()
        root.postDelayed(hideSettingsRunnable, HIDE_SETTINGS_AFTER_MS)
        root.postDelayed(dimRunnable, DIM_AFTER_MS)
        root.postDelayed(longDimRunnable, LONG_DIM_AFTER_MS)
    }

    /**
     * Cambio de estado del orbe: mantiene la pantalla despierta y reinicia la
     * atenuación, pero no vuelve a mostrar los tres puntos. Así el botón se
     * oculta 30 s después del último toque aunque la conversación siga.
     */
    fun onStateChange() {
        if (!foreground) return
        root.removeCallbacks(dimRunnable)
        root.removeCallbacks(longDimRunnable)
        restoreBrightness()
        root.postDelayed(dimRunnable, DIM_AFTER_MS)
        root.postDelayed(longDimRunnable, LONG_DIM_AFTER_MS)
    }

    private fun dimScreen() {
        if (!foreground || screenDimmed || screenVeryDimmed) return
        if (isSettingsOpen()) {
            root.postDelayed(dimRunnable, DIM_AFTER_MS)
            return
        }
        setWindowBrightness(DIM_BRIGHTNESS)
        screenDimmed = true
        Log.i("Samantha", "OLED: pantalla atenuada")
        hideSettingsButton()
    }

    private fun dimMore() {
        if (!foreground || screenVeryDimmed) return
        if (isSettingsOpen()) return
        setWindowBrightness(LONG_DIM_BRIGHTNESS)
        screenDimmed = true
        screenVeryDimmed = true
        Log.i("Samantha", "OLED: pantalla muy atenuada")
        hideSettingsButton()
    }

    private fun restoreBrightness() {
        if (!screenDimmed && !screenVeryDimmed) return
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        screenDimmed = false
        screenVeryDimmed = false
        Log.i("Samantha", "OLED: brillo restaurado")
    }

    private fun setWindowBrightness(value: Float) {
        val attrs = activity.window.attributes
        attrs.screenBrightness = value
        activity.window.attributes = attrs
    }

    private fun hideSettingsButton() {
        if (isSettingsOpen()) return
        if (settingsButton.visibility != View.VISIBLE) return
        settingsButton.animate().cancel()
        settingsButton.animate()
            .alpha(0f)
            .setDuration(250L)
            .withEndAction {
                settingsButton.visibility = View.INVISIBLE
                settingsButton.alpha = 1f
            }
            .start()
        Log.i("Samantha", "OLED: ajustes ocultos")
    }

    private fun showSettingsButton() {
        settingsButton.animate().cancel()
        settingsButton.alpha = 1f
        settingsButton.visibility = View.VISIBLE
    }

    private companion object {
        const val DIM_AFTER_MS = 3 * 60 * 1000L
        const val LONG_DIM_AFTER_MS = 60 * 60 * 1000L
        const val HIDE_SETTINGS_AFTER_MS = 30_000L
        const val ORB_DRIFT_INTERVAL_MS = 2 * 60 * 1000L
        const val DIM_BRIGHTNESS = 0.18f
        const val LONG_DIM_BRIGHTNESS = 0.05f
    }
}
