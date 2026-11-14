package com.cdcb.samanthapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Orbe de estado: port fiel de `openocto/ui/orb.py` (proyecto de PC).
 *
 * - Cuerpo plano por 44 capas concéntricas con viñeta y borde de vidrio
 *   (sin brillo interior), con deformación orgánica leve.
 * - Halo de 22 capas + anillo tenue.
 * - Infinito crema (lemniscata de Gerono) con sombra fina.
 * - Efectos por estado: corona de barras, ondas, motas, cometas, arcos.
 *
 * La unidad de referencia es la ventana de 220 px del Python: todo el trazo
 * se escala con `u = mitad / 110` para que se vea igual a cualquier tamaño.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class State { WAITING, READY, LISTENING, HEARING, TRANSCRIBING, THINKING, SPEAKING, ERROR }

    private data class Estilo(
        val color: Int,
        val acento: Int,
        val ritmo: Float,
        val radio: Float,
        val ganancia: Float,
        val brillo: Float,
    )

    private data class Parametros(
        val estilo: Estilo,
        val color: Int,
        val acento: Int,
        val radio: Float,
        val brillo: Float,
        val angulo: Float,
        val fase: Float,
    )

    private val estilos = mapOf(
        State.LISTENING to Estilo(
            0xFF22D3EE.toInt(), 0xFF67E8F9.toInt(), 0.35f, 0.50f, 0.00f, 0.85f
        ),
        State.HEARING to Estilo(
            0xFF2DD4BF.toInt(), 0xFF99F6E4.toInt(), 0.90f, 0.38f, 0.55f, 1.00f
        ),
        State.TRANSCRIBING to Estilo(
            0xFFA78BFA.toInt(), 0xFFC4B5FD.toInt(), 0.80f, 0.52f, 0.00f, 0.90f
        ),
        State.THINKING to Estilo(
            0xFFFBBF24.toInt(), 0xFFFDE68A.toInt(), 1.40f, 0.50f, 0.00f, 0.95f
        ),
        State.SPEAKING to Estilo(
            0xFFF472B6.toInt(), 0xFFF9A8D4.toInt(), 1.00f, 0.52f, 0.00f, 1.00f
        ),
        State.WAITING to Estilo(
            0xFF64748B.toInt(), 0xFF94A3B8.toInt(), 0.20f, 0.42f, 0.00f, 0.45f
        ),
        State.READY to Estilo(
            0xFF3B82F6.toInt(), 0xFF93C5FD.toInt(), 0.20f, 0.42f, 0.00f, 0.55f
        ),
        State.ERROR to Estilo(
            0xFFF87171.toInt(), 0xFFFCA5A5.toInt(), 2.00f, 0.55f, 0.00f, 1.00f
        ),
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private val cosTable = FloatArray(PUNTOS_CUERPO) {
        cos(2.0 * PI * it / PUNTOS_CUERPO).toFloat()
    }
    private val sinTable = FloatArray(PUNTOS_CUERPO) {
        sin(2.0 * PI * it / PUNTOS_CUERPO).toFloat()
    }

    private var level = 0f
    private var smoothLevel = 0f
    private var driftX = 0f
    private var driftY = 0f
    private val startNanos = System.nanoTime()

    private var colorFrom = estilos.getValue(State.WAITING).color
    private var colorTo = colorFrom
    private var accentFrom = estilos.getValue(State.WAITING).acento
    private var accentTo = accentFrom
    private var colorMix = 1f
    private var colorAnimator: ValueAnimator? = null
    private var rippleStartNanos = 0L
    private var pressed = false

    var state: State = State.WAITING
        set(value) {
            if (field == value) return
            val baseActual = currentColor()
            val acentoActual = currentAccent()
            field = value
            colorFrom = baseActual
            accentFrom = acentoActual
            val estilo = estilos.getValue(value)
            colorTo = estilo.color
            accentTo = estilo.acento
            colorMix = 0f
            colorAnimator?.cancel()
            colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                addUpdateListener {
                    colorMix = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            invalidate()
        }

    private fun currentColor(): Int =
        ColorUtils.blendARGB(colorFrom, colorTo, colorMix)

    private fun currentAccent(): Int =
        ColorUtils.blendARGB(accentFrom, accentTo, colorMix)

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        contentDescription = "Orbe de Samantha"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ancho = MeasureSpec.getSize(widthMeasureSpec)
        val alto = MeasureSpec.getSize(heightMeasureSpec)
        val modoAncho = MeasureSpec.getMode(widthMeasureSpec)
        val modoAlto = MeasureSpec.getMode(heightMeasureSpec)
        val maximo = dp(420)
        val lado = when {
            modoAncho == MeasureSpec.UNSPECIFIED && modoAlto == MeasureSpec.UNSPECIFIED ->
                dp(340)
            modoAncho == MeasureSpec.UNSPECIFIED -> min(alto, maximo)
            modoAlto == MeasureSpec.UNSPECIFIED -> min(ancho, maximo)
            else -> min(min(ancho, alto), maximo)
        }.coerceAtLeast(dp(120))
        setMeasuredDimension(lado, lado)
    }

    private fun dp(valor: Int): Int =
        (valor * resources.displayMetrics.density).roundToInt()

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
    }

    /** Desplazamiento lento para evitar que un píxel reciba siempre el brillo. */
    fun setDrift(x: Float, y: Float) {
        if (driftX == x && driftY == y) return
        driftX = x
        driftY = y
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                rippleStartNanos = System.nanoTime()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                pressed = false
                performClick()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        // Ataque rápido y caída lenta: reacciona a la voz sin rebotar.
        smoothLevel = if (level > smoothLevel) {
            smoothLevel + (level - smoothLevel) * 0.45f
        } else {
            smoothLevel + (level - smoothLevel) * 0.12f
        }
        val p = parametros(state, t, smoothLevel)

        val cx = width / 2f
        val cy = height / 2f
        val mitad = min(width, height) / 2f
        // Referencia: ventana de 220 px del orbe original (margen 16 px).
        // ESCALA agranda el orbe manteniendo las proporciones del Python; el
        // trazo y los efectos crecen con él, pero el margen no.
        val uBase = mitad / 110f
        val u = uBase * ESCALA
        val rBruto = (mitad - 16f * uBase) * p.radio * ESCALA
        // El halo y la corona ocupan hasta ~1.28 r: se limita el radio para
        // que quepan en la vista y el orbe nunca se recorte en cuadrado.
        val rMax = (mitad - 6f * u) / 1.28f
        val escalaPulsacion = if (pressed) 0.985f else 1f
        val r = min(rBruto, rMax) * escalaPulsacion
        if (r <= 0f) return

        canvas.save()
        canvas.translate(driftX, driftY)
        val base = p.color
        val acento = p.acento

        // Resplandor ambiental: llena la vista con el color del estado.
        // El radio termina antes del borde de la vista para que el recorte
        // cuadrado no sea visible sobre el fondo animado.
        softPaint.shader = RadialGradient(
            cx, cy, mitad * 0.98f,
            intArrayOf(withAlpha(acento, 20), withAlpha(acento, 6), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, mitad * 0.98f, softPaint)

        // Sombra suave bajo el orbe: le da profundidad sobre el fondo.
        softPaint.shader = RadialGradient(
            cx, cy + r * 0.62f, r * 0.95f,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + r * 0.62f, r * 0.95f, softPaint)

        // Halo: 22 capas que se apagan hacia afuera + anillo tenue.
        val haloColor = mix(base, acento, 0.35f)
        for (k in 22 downTo 1) {
            val f = k / 22f
            val rr = r * (1f + 0.20f * f)
            val alfa = (118f * p.brillo * (1f - f).pow(2f)).toInt()
            if (alfa <= 0) continue
            fillPaint.color = withAlpha(haloColor, alfa)
            canvas.drawCircle(cx, cy, rr, fillPaint)
        }
        strokePaint.strokeWidth = max(1f, 1f * u)
        strokePaint.color = withAlpha(acento, (42f * p.brillo).toInt())
        canvas.drawCircle(cx, cy, r * 1.20f, strokePaint)

        // Cuerpo: 44 capas con viñeta y borde de vidrio.
        val oscuro = mix(base, 0xFF04060B.toInt(), 0.62f)
        val borde = mix(base, acento, 0.22f)
        val deformacion = if (state == State.HEARING || state == State.SPEAKING) {
            0.020f
        } else {
            0.010f
        }
        for (i in PASOS_CUERPO downTo 1) {
            val f = i / PASOS_CUERPO.toFloat()
            val rr = r * f
            val tt = (1f - f).pow(0.55f)
            var col = mix(oscuro, base, min(1f, 0.30f + 0.70f * tt))
            if (f > 0.84f) col = mix(col, borde, (f - 0.84f) * 1.4f)
            fillPaint.color = col
            buildOrbita(cx, cy, rr, p.fase, deformacion)
            canvas.drawPath(path, fillPaint)
        }

        // Canto: sombra abajo-derecha, luz arriba-izquierda y borde de vidrio.
        rect.set(cx - r * 0.97f, cy - r * 0.97f, cx + r * 0.97f, cy + r * 0.97f)
        strokePaint.strokeWidth = max(2f, 5f * u)
        strokePaint.color = withAlpha(oscuro, 150)
        canvas.drawArc(rect, 15f, 150f, false, strokePaint)
        strokePaint.strokeWidth = max(1f, 2f * u)
        strokePaint.color = withAlpha(acento, 90)
        canvas.drawArc(rect, 198f, 144f, false, strokePaint)
        strokePaint.strokeWidth = max(1f, 1.2f * u)
        strokePaint.color = withAlpha(acento, 32)
        canvas.drawArc(rect, 20f, 130f, false, strokePaint)
        strokePaint.strokeWidth = max(1f, 1f * u)
        strokePaint.color = withAlpha(acento, 42)
        canvas.drawOval(rect, strokePaint)

        // Infinito crema con su sombra fina.
        buildSimbolo(cx, cy, r)
        symbolPaint.strokeWidth = max(1f, 2.6f * u)
        symbolPaint.color = withAlpha(mix(base, Color.BLACK, 0.55f), 80)
        canvas.drawPath(path, symbolPaint)
        symbolPaint.strokeWidth = max(1f, 1.7f * u)
        symbolPaint.color = withAlpha(0xFFF3E2BD.toInt(), 235)
        canvas.drawPath(path, symbolPaint)

        // Efectos por estado.
        if (state == State.HEARING || state == State.SPEAKING) {
            drawCorona(canvas, cx, cy, r, u, p, t)
        }
        when (state) {
            State.SPEAKING -> drawOndas(canvas, cx, cy, r, u, p, t)
            State.LISTENING -> drawListening(canvas, cx, cy, r, u, p)
            State.TRANSCRIBING -> drawTranscribing(canvas, cx, cy, r, u, p)
            State.THINKING -> drawThinking(canvas, cx, cy, r, u, p)
            State.WAITING -> drawWaiting(canvas, cx, cy, r, u, p)
            State.READY -> drawWaiting(canvas, cx, cy, r, u, p)
            State.ERROR -> drawError(canvas, cx, cy, r, u, p, t)
            else -> Unit
        }

        // Detalles de reposo: borde que gira y polvo orbital muy tenue.
        if (state == State.WAITING || state == State.READY) {
            drawRim(canvas, cx, cy, r, u, p, t)
            drawDust(canvas, cx, cy, r, u, p, t)
        }
        drawRipple(canvas, cx, cy, r, u, p)
        canvas.restore()
    }

    // ── parámetros (mismo cálculo que orb.py) ─────────────────────────

    private fun parametros(state: State, t: Float, nivel: Float): Parametros {
        val estilo = estilos[state] ?: estilos.getValue(State.WAITING)
        val fase = 2f * PI.toFloat() * estilo.ritmo * t
        var radio = estilo.radio +
            estilo.ganancia * nivel +
            0.032f * sin(fase) +
            0.010f * sin(2.3f * fase + 0.7f)
        when (state) {
            State.SPEAKING -> radio += 0.045f * sin(3f * fase + 0.4f)
            State.HEARING -> radio += 0.010f * sin(5f * fase)
            State.ERROR -> radio += 0.010f * sin(7f * fase)
            else -> Unit
        }
        radio = radio.coerceIn(0.25f, 0.95f)
        val brillo = (estilo.brillo * (0.82f + 0.18f * sin(fase + 0.3f)))
            .coerceIn(0f, 1f)
        return Parametros(
            estilo = estilo,
            color = currentColor(),
            acento = currentAccent(),
            radio = radio,
            brillo = brillo,
            angulo = (360f * estilo.ritmo * t) % 360f,
            fase = fase,
        )
    }

    // ── geometría ─────────────────────────────────────────────────────

    private fun buildOrbita(
        cx: Float,
        cy: Float,
        radio: Float,
        fase: Float,
        deformacion: Float,
    ) {
        path.reset()
        for (k in 0 until PUNTOS_CUERPO) {
            val a = 2f * PI.toFloat() * k / PUNTOS_CUERPO
            val rr = radio * (
                1f +
                    deformacion * sin(3f * a + fase) +
                    0.004f * sin(5f * a - fase * 1.7f)
                )
            val x = cx + rr * cosTable[k]
            val y = cy + rr * sinTable[k]
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun buildSimbolo(cx: Float, cy: Float, radio: Float) {
        path.reset()
        val ancho = radio * 0.62f
        val alto = radio * 0.46f
        for (k in 0..PUNTOS_SIMBOLO) {
            val a = 2f * PI.toFloat() * k / PUNTOS_SIMBOLO
            val x = cx + ancho * cos(a)
            val y = cy + alto * sin(a) * cos(a)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    // ── efectos ───────────────────────────────────────────────────────

    private fun drawCorona(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
        t: Float,
    ) {
        val n = 26
        val r0 = r * 1.08f
        strokePaint.strokeWidth = max(1f, 1.5f * u)
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n - PI.toFloat() / 2f
            val energia = if (state == State.SPEAKING) {
                0.30f + 0.45f * abs(sin(p.fase * 1.7f + i * 0.35f))
            } else {
                max(0.05f, smoothLevel)
            }
            val h = (
                1f +
                    7.5f * energia *
                    (0.35f + 0.65f * abs(sin(p.fase * 2f + i * 0.9f)))
                ) * u
            val x0 = cx + r0 * cos(a)
            val y0 = cy + r0 * sin(a)
            val x1 = cx + (r0 + h) * cos(a)
            val y1 = cy + (r0 + h) * sin(a)
            strokePaint.color = withAlpha(
                p.acento,
                (105f + 105f * min(1f, energia)).toInt(),
            )
            canvas.drawLine(x0, y0, x1, y1, strokePaint)
        }
    }

    private fun drawOndas(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
        t: Float,
    ) {
        strokePaint.strokeWidth = max(1f, 1.2f * u)
        for (k in 0 until 3) {
            val f = (t * 0.55f + k / 3f) % 1f
            val rr = r * (0.98f + 0.30f * f)
            val alfa = (70f * (1f - f) * p.brillo).toInt()
            if (alfa <= 0) continue
            strokePaint.color = withAlpha(p.acento, alfa)
            canvas.drawCircle(cx, cy, rr, strokePaint)
        }
    }

    private fun drawListening(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
    ) {
        val rr = r * 1.13f
        fillPaint.color = withAlpha(p.acento, 150)
        for (k in 0 until 2) {
            val a = Math.toRadians((p.angulo * 0.7f + k * 180f).toDouble()).toFloat()
            canvas.drawCircle(
                cx + rr * cos(a),
                cy + rr * sin(a),
                max(2f, 2.6f * u),
                fillPaint,
            )
        }
        rect.set(
            cx - rr * 1.06f, cy - rr * 1.06f,
            cx + rr * 1.06f, cy + rr * 1.06f,
        )
        strokePaint.strokeWidth = max(1f, 1.2f * u)
        strokePaint.color = withAlpha(p.acento, 65)
        canvas.drawArc(rect, p.angulo, 58f, false, strokePaint)
    }

    private fun drawTranscribing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
    ) {
        val rr = r * 1.12f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        strokePaint.strokeWidth = max(2f, 2.8f * u)
        strokePaint.color = withAlpha(p.acento, 210)
        canvas.drawArc(rect, p.angulo, 105f, false, strokePaint)
        strokePaint.strokeWidth = max(1f, 1.4f * u)
        strokePaint.color = withAlpha(p.color, 120)
        canvas.drawArc(rect, -p.angulo * 1.55f + 30f, 175f, false, strokePaint)
        fillPaint.color = withAlpha(p.acento, 230)
        for (k in 0 until 2) {
            val grados = p.angulo + if (k == 0) 105f else 0f
            val a = Math.toRadians(grados.toDouble()).toFloat()
            canvas.drawCircle(
                cx + rr * cos(a),
                cy + rr * sin(a),
                max(1.5f, 2f * u),
                fillPaint,
            )
        }
    }

    private fun drawThinking(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
    ) {
        val rr = r * 1.13f
        for (k in 0 until 3) {
            val a0 = Math.toRadians((p.angulo * 1.35f + 120f * k).toDouble()).toFloat()
            for (cola in 6 downTo 1) {
                val a = a0 - cola * 0.15f
                val rad = rr * (1f - 0.012f * cola)
                val x = cx + rad * cos(a)
                val y = cy + rad * sin(a)
                fillPaint.color = withAlpha(
                    p.acento,
                    (155f * (1f - cola / 7f)).toInt(),
                )
                canvas.drawCircle(x, y, max(1.2f, (4.6f - 0.55f * cola) * u), fillPaint)
            }
            val x = cx + rr * cos(a0)
            val y = cy + rr * sin(a0)
            fillPaint.color = withAlpha(Color.WHITE, 235)
            canvas.drawCircle(x, y, max(2.2f, 4.8f * u), fillPaint)
        }
        val rr2 = rr * 0.86f
        rect.set(cx - rr2, cy - rr2, cx + rr2, cy + rr2)
        strokePaint.strokeWidth = max(1f, 1f * u)
        strokePaint.color = withAlpha(p.acento, 55)
        canvas.drawArc(rect, -p.angulo * 0.8f, 120f, false, strokePaint)
    }

    private fun drawWaiting(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
    ) {
        val rr = r * 1.10f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        strokePaint.strokeWidth = max(1f, 1f * u)
        strokePaint.color = withAlpha(p.acento, 70)
        canvas.drawArc(rect, p.angulo, 35f, false, strokePaint)
        fillPaint.color = withAlpha(p.acento, 80)
        for (k in 0 until 2) {
            val a = Math.toRadians((p.angulo * 0.5f + 180f * k).toDouble()).toFloat()
            canvas.drawCircle(
                cx + rr * cos(a),
                cy + rr * sin(a),
                max(1.5f, 2f * u),
                fillPaint,
            )
        }
    }

    private fun drawError(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
        t: Float,
    ) {
        val rr = r * 1.10f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        strokePaint.strokeWidth = max(2f, 2.6f * u)
        strokePaint.color = withAlpha(p.acento, 185)
        for (k in 0 until 12) {
            val a0 = p.angulo * 1.8f + k * 30f
            canvas.drawArc(rect, a0, 18f, false, strokePaint)
        }
        strokePaint.strokeWidth = max(1f, 1.5f * u)
        for (k in 0 until 2) {
            val f = (t * 0.9f + k / 2f) % 1f
            val rad = r * (0.98f + 0.22f * f)
            val alfa = (85f * (1f - f)).toInt()
            if (alfa <= 0) continue
            strokePaint.color = withAlpha(0xFFFF3B30.toInt(), alfa)
            canvas.drawCircle(cx, cy, rad, strokePaint)
        }
    }

    /** Arco de luz que gira lentamente por el borde (reposo). */
    private fun drawRim(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
        t: Float,
    ) {
        val rr = r * 1.16f
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        strokePaint.strokeWidth = max(1f, 1.4f * u)
        strokePaint.color = withAlpha(p.acento, 28)
        canvas.drawArc(rect, (t * 12f) % 360f, 42f, false, strokePaint)
    }

    /** Polvo orbital muy tenue alrededor del orbe en reposo. */
    private fun drawDust(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
        t: Float,
    ) {
        val n = 10
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n + t * 0.06f
            val rad = r * (1.16f + 0.035f * sin(t * 0.6f + i * 1.1f))
            val alfa = (16f + 14f * sin(t * 0.9f + i * 1.7f)).toInt().coerceAtLeast(4)
            fillPaint.color = withAlpha(p.acento, alfa)
            canvas.drawCircle(
                cx + rad * cos(a),
                cy + rad * sin(a),
                max(1f, 1.1f * u),
                fillPaint,
            )
        }
    }

    /** Onda que sale del orbe al tocarlo. */
    private fun drawRipple(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        u: Float,
        p: Parametros,
    ) {
        if (rippleStartNanos == 0L) return
        val elapsed = (System.nanoTime() - rippleStartNanos) / 1_000_000_000f
        if (elapsed > 0.5f) return
        val f = elapsed / 0.5f
        val radioMax = min(canvas.width, canvas.height) / 2f - 4f * u
        val rr = min(r * (1f + 0.55f * f), radioMax)
        if (rr <= 0f) return
        strokePaint.strokeWidth = max(1f, 2f * u * (1f - f) + 1f)
        strokePaint.color = withAlpha(p.acento, ((1f - f).pow(2f) * 110f).toInt())
        canvas.drawCircle(cx, cy, rr, strokePaint)
    }

    // ── utilidades ────────────────────────────────────────────────────

    private fun mix(c1: Int, c2: Int, t: Float): Int =
        ColorUtils.blendARGB(c1, c2, t.coerceIn(0f, 1f))

    private fun withAlpha(color: Int, alpha: Int): Int =
        ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))

    private companion object {
        const val PASOS_CUERPO = 44
        const val PUNTOS_CUERPO = 64
        const val PUNTOS_SIMBOLO = 180
        // El orbe del Python está pensado para una ventana pequeña; en pantalla
        // completa se ve mejor un 45 % más grande sin cambiar su geometría.
        const val ESCALA = 1.75f
    }
}
