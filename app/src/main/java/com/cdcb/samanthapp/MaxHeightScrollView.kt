package com.cdcb.samanthapp

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scroll vertical que se ajusta al contenido pero nunca crece más allá de
 * [maxHeight]. Así las respuestas cortas no dejan hueco y las largas se
 * desplazan dentro de la tarjeta en vez de cortarse con puntos suspensivos.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val altoMaximo: Int = dp(240)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val disponible = MeasureSpec.getSize(heightMeasureSpec)
        val alto = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            altoMaximo
        } else {
            min(disponible, altoMaximo)
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(alto, MeasureSpec.AT_MOST)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
