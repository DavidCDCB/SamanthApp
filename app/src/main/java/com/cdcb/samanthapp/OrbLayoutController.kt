package com.cdcb.samanthapp

import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cdcb.samanthapp.databinding.ActivityMainBinding

/**
 * Colocación del orbe y de las tarjetas.
 *
 * - En vertical: orbe arriba, tarjetas debajo.
 * - En horizontal: orbe centrado si no hay tarjetas; si las hay, mitad y mitad.
 * - Mantiene el halo del fondo alineado con el centro real del orbe.
 * - Aplica los insets del sistema al contenido, no al fondo.
 */
class OrbLayoutController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {

    private val density = activity.resources.displayMetrics.density

    fun install() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val tipos = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            // Las barras están ocultas (inmersivo), así que getInsets() devuelve
            // 0. Cuando el orbe crece, su corona se dibujaba debajo de la barra
            // de estado al desplegarla. Se reserva el tamaño real de las barras
            // aunque estén ocultas para que nada quede en esa franja.
            val bars = Insets.max(
                insets.getInsets(tipos),
                insets.getInsetsIgnoringVisibility(tipos),
            )
            val horizontal = activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            binding.contentScroll.setPadding(
                bars.left + dp(8),
                // En horizontal el orbe se centra en la pantalla y la corona
                // cabe bajo la barra; no hace falta el margen extra de 8 dp.
                bars.top + if (horizontal) 0 else dp(8),
                bars.right + dp(8),
                bars.bottom + dp(16),
            )
            val parametros =
                binding.settingsButton.layoutParams as FrameLayout.LayoutParams
            parametros.topMargin = bars.top + dp(2)
            parametros.marginEnd = bars.right + dp(6)
            binding.settingsButton.layoutParams = parametros
            binding.root.post { syncFocus() }
            insets
        }
        binding.orb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFocus()
        }
        binding.root.post { syncFocus() }
    }

    fun applyOrientation(orientacion: Int) {
        val horizontal = orientacion == Configuration.ORIENTATION_LANDSCAPE
        binding.contentColumn.orientation =
            if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.contentColumn.gravity = if (horizontal) {
            Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }

        val lpOrbe = binding.orbHolder.layoutParams as LinearLayout.LayoutParams
        val lpTarjetas = binding.cardsColumn.layoutParams as LinearLayout.LayoutParams
        if (horizontal) {
            // El orbe usa todo el alto visible; las tarjetas conservan su
            // margen superior con padding propio.
            binding.contentColumn.setPadding(
                binding.contentColumn.paddingLeft,
                0,
                binding.contentColumn.paddingRight,
                0,
            )
            binding.cardsColumn.setPadding(0, dp(24), 0, 0)
            lpOrbe.width = 0
            lpOrbe.height = altoOrbe().takeIf { it > 0 }
                ?: LinearLayout.LayoutParams.MATCH_PARENT
            lpOrbe.weight = 1f
            binding.orbHolder.setPadding(0, 0, 0, 0)
            lpTarjetas.width = 0
            lpTarjetas.height = LinearLayout.LayoutParams.MATCH_PARENT
            lpTarjetas.weight = 1f
            binding.cardsColumn.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            binding.contentColumn.setPadding(
                binding.contentColumn.paddingLeft,
                dp(24),
                binding.contentColumn.paddingRight,
                dp(24),
            )
            binding.cardsColumn.setPadding(0, 0, 0, 0)
            lpOrbe.width = LinearLayout.LayoutParams.MATCH_PARENT
            lpOrbe.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lpOrbe.weight = 0f
            binding.orbHolder.setPadding(0, 0, 0, 0)
            lpTarjetas.width = LinearLayout.LayoutParams.MATCH_PARENT
            lpTarjetas.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lpTarjetas.weight = 0f
            binding.cardsColumn.gravity = Gravity.CENTER_VERTICAL
        }
        binding.orbHolder.layoutParams = lpOrbe
        binding.cardsColumn.layoutParams = lpTarjetas

        val lpRespuesta =
            binding.responseScroll.layoutParams as LinearLayout.LayoutParams
        if (horizontal) {
            lpRespuesta.height = 0
            lpRespuesta.weight = 1f
        } else {
            lpRespuesta.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lpRespuesta.weight = 0f
        }
        binding.responseScroll.layoutParams = lpRespuesta

        rebalance()
    }

    /**
     * En horizontal, si no hay tarjetas visibles el orbe ocupa toda la
     * pantalla; cuando aparece una transcripción o respuesta, se reparte el
     * ancho entre orbe y tarjetas.
     */
    fun rebalance() {
        if (activity.resources.configuration.orientation !=
            Configuration.ORIENTATION_LANDSCAPE
        ) {
            return
        }
        val hayTarjetas = hasTextCards()
        val lpOrbe = binding.orbHolder.layoutParams as LinearLayout.LayoutParams
        val lpTarjetas = binding.cardsColumn.layoutParams as LinearLayout.LayoutParams
        lpOrbe.width = 0
        // No se vuelve a MATCH_PARENT: el alto lo fija fitLandscapeHolder()
        // al hueco visible; si no, el orbe crece y el scroll lo recorta.
        lpOrbe.height = altoOrbe().takeIf { it > 0 }
            ?: LinearLayout.LayoutParams.MATCH_PARENT
        lpOrbe.weight = 1f
        lpTarjetas.width = 0
        lpTarjetas.height = LinearLayout.LayoutParams.MATCH_PARENT
        lpTarjetas.weight = if (hayTarjetas) 1f else 0f
        binding.orbHolder.layoutParams = lpOrbe
        binding.cardsColumn.layoutParams = lpTarjetas
        binding.root.post { syncFocus() }
    }

    /** Solo cuentan las tarjetas que de verdad muestran texto. */
    private fun hasTextCards(): Boolean =
        (binding.transcript.visibility == View.VISIBLE &&
            binding.transcript.text.isNotBlank()) ||
            (binding.responseScroll.visibility == View.VISIBLE &&
                binding.response.text.isNotBlank())

    /** Centra el halo del fondo en el orbe real. */
    fun syncFocus() {
        fitLandscapeHolder()
        centerOrbVertically()
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

    /**
     * En horizontal el contenedor se estiraba más que el área visible: el
     * orbe se medía con el ancho disponible (420 dp) y el scroll recortaba su
     * parte superior. Se fija el alto al hueco real entre insets para que el
     * orbe y su corona queden completos.
     */
    private fun fitLandscapeHolder() {
        if (activity.resources.configuration.orientation !=
            Configuration.ORIENTATION_LANDSCAPE
        ) {
            return
        }
        val altoVisible = altoOrbe()
        if (altoVisible <= 0) return
        val lp = binding.orbHolder.layoutParams as LinearLayout.LayoutParams
        if (lp.height == altoVisible) return
        lp.height = altoVisible
        binding.orbHolder.layoutParams = lp
    }

    /** Alto real disponible para el orbe, ya descontados insets y paddings. */
    private fun altoOrbe(): Int =
        binding.contentScroll.height -
            binding.contentScroll.paddingTop -
            binding.contentScroll.paddingBottom -
            binding.contentColumn.paddingTop -
            binding.contentColumn.paddingBottom

    /**
     * En horizontal el orbe se centra en la pantalla. El contenido reserva la
     * franja de la barra de estado para las tarjetas y los ajustes, pero el
     * orbe no baja con ese padding: su corona sigue quedando por debajo de la
     * barra y visualmente queda centrado.
     */
    private fun centerOrbVertically() {
        val orbe = binding.orbHolder
        if (activity.resources.configuration.orientation !=
            Configuration.ORIENTATION_LANDSCAPE
        ) {
            orbe.translationY = 0f
            return
        }
        if (binding.root.height == 0 || orbe.height == 0) return
        orbe.translationY = 0f
        val posOrbe = IntArray(2)
        val posRaiz = IntArray(2)
        orbe.getLocationInWindow(posOrbe)
        binding.root.getLocationInWindow(posRaiz)
        val centroPantalla = posRaiz[1] + binding.root.height / 2f
        val centroOrbe = posOrbe[1] + orbe.height / 2f
        orbe.translationY = centroPantalla - centroOrbe
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
