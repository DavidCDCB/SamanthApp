package com.cdcb.samanthapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import java.util.Calendar

/**
 * Fondo animado a pantalla completa detrás del orbe.
 *
 * Capas, de atrás hacia delante:
 *  - Base oscura con viñeta y el tono del estado.
 *  - Aurora: manchas de luz que se desplazan y respiran muy despacio.
 *  - Cortinas: bandas alargadas que flotan como aurora boreal.
 *  - Halo: resplandor que late detrás del orbe.
 *  - Polvo: partículas con parpadeo y deriva.
 *  - Luciérnagas: motas mayores con brillo suave.
 *  - Anillos: ondas lentas en reposo y reactivas al hablar/escuchar.
 *  - Cometa: estrella fugaz ocasional solo en reposo.
 *  - Onda al tocar el orbe o activar la wake word.
 *
 * Se dibuja a 30 fps (15 fps en bajo consumo), no asigna gradientes por
 * fotograma (usa un bitmap de resplandor cacheado), no intercepta toques y se
 * detiene cuando la actividad no está visible.
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { ON, LOW, OFF }

    private data class Estilo(
        val color: Int,
        val acento: Int,
        val secundario: Int,
        val fondo: Int,
    )

    /** Parámetros fijos de cada mancha de aurora. */
    private data class Semilla(
        val radioX: Float,
        val radioY: Float,
        val fase: Float,
        val velocidad: Float,
        val giro: Float,
        val alfa: Float,
    )

    private data class Particula(
        val x: Float,
        val y: Float,
        val fase: Float,
        val deriva: Float,
        val tamano: Float,
        val parpadeo: Float,
    )

    private data class Luciernaga(
        val x: Float,
        val y: Float,
        val fase: Float,
        val velocidad: Float,
        val amplitud: Float,
        val tamano: Float,
    )

    private val estilos = mapOf(
        OrbView.State.LISTENING to Estilo(
            0xFF22D3EE.toInt(), 0xFF67E8F9.toInt(), 0xFF818CF8.toInt(), 0xFF04141C.toInt()
        ),
        OrbView.State.HEARING to Estilo(
            0xFF2DD4BF.toInt(), 0xFF99F6E4.toInt(), 0xFF22D3EE.toInt(), 0xFF041614.toInt()
        ),
        OrbView.State.TRANSCRIBING to Estilo(
            0xFFA78BFA.toInt(), 0xFFC4B5FD.toInt(), 0xFF22D3EE.toInt(), 0xFF0A0718.toInt()
        ),
        OrbView.State.THINKING to Estilo(
            0xFFFBBF24.toInt(), 0xFFFDE68A.toInt(), 0xFFF97316.toInt(), 0xFF120D04.toInt()
        ),
        OrbView.State.SPEAKING to Estilo(
            0xFFF472B6.toInt(), 0xFFF9A8D4.toInt(), 0xFFA78BFA.toInt(), 0xFF13060F.toInt()
        ),
        OrbView.State.WAITING to Estilo(
            0xFF64748B.toInt(), 0xFF94A3B8.toInt(), 0xFF38BDF8.toInt(), 0xFF080B12.toInt()
        ),
        OrbView.State.READY to Estilo(
            0xFF3B82F6.toInt(), 0xFF93C5FD.toInt(), 0xFF22D3EE.toInt(), 0xFF050B18.toInt()
        ),
        OrbView.State.ERROR to Estilo(
            0xFFF87171.toInt(), 0xFFFCA5A5.toInt(), 0xFFFB923C.toInt(), 0xFF120606.toInt()
        ),
    )

    private val semillas = listOf(
        Semilla(0.58f, 0.40f, 0.00f, 1.00f, -10f, 48f),
        Semilla(0.46f, 0.34f, 2.30f, 1.35f, 24f, 38f),
        Semilla(0.34f, 0.28f, 4.10f, 0.82f, -20f, 30f),
        Semilla(0.24f, 0.20f, 5.40f, 1.62f, 12f, 24f),
    )

    private val particulas = List(42) { i ->
        val r = Random(i * 7919 + 13)
        Particula(
            x = r.nextFloat(),
            y = r.nextFloat(),
            fase = r.nextFloat() * 6.283f,
            deriva = 0.35f + r.nextFloat() * 0.65f,
            tamano = 0.6f + r.nextFloat() * 1.5f,
            parpadeo = 0.35f + r.nextFloat() * 1.25f,
        )
    }

    private val luciernagas = List(6) { i ->
        val r = Random(i * 104729 + 71)
        Luciernaga(
            x = 0.12f + r.nextFloat() * 0.76f,
            y = 0.14f + r.nextFloat() * 0.68f,
            fase = r.nextFloat() * 6.283f,
            velocidad = 0.05f + r.nextFloat() * 0.09f,
            amplitud = 0.04f + r.nextFloat() * 0.07f,
            tamano = 0.8f + r.nextFloat() * 1.1f,
        )
    }

    private val blob: Bitmap = crearBlob()

    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val luciernagaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Filtros de tinte cacheados por ranura: evita crear uno por fotograma.
    private val tintes = IntArray(8) { 1 }
    private val filtros = arrayOfNulls<PorterDuffColorFilter>(8)

    private var mode = Mode.ON
    private var frameIntervalMs = 33L
    private var running = false
    private var attached = false
    private var paused = false
    private var ultimoDibujoMs = 0L
    private val inicioMs = SystemClock.elapsedRealtime()
    private val densidad = resources.displayMetrics.density

    private var paletaHora = FondoHorario.paletaDe(
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    )
    private var colorFrom = paletaHora.base
    private var colorTo = colorFrom
    private var accentFrom = paletaHora.acento
    private var accentTo = accentFrom
    private var secundarioFrom = paletaHora.secundario
    private var secundarioTo = secundarioFrom
    private var fondoFrom = paletaHora.fondo
    private var fondoTo = fondoFrom
    private var colorMix = 1f
    private var colorAnimator: ValueAnimator? = null
    private var proximaComprobacionHoraMs = 0L

    private var nivel = 0f
    private var nivelSuave = 0f

    private var focoX = -1f
    private var focoY = -1f

    private var rippleStartMs = 0L

    private var proximoCometaMs = 0L
    private var cometaInicioMs = -1L
    private var cometaSemilla = 0

    var state: OrbView.State = OrbView.State.WAITING
        set(value) {
            if (field == value) return
            field = value
            animarHacia(estiloDe(value), 520L)
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val ahora = SystemClock.elapsedRealtime()
            if (ultimoDibujoMs == 0L || ahora - ultimoDibujoMs >= frameIntervalMs) {
                ultimoDibujoMs = ahora
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMode(nuevo: String) {
        mode = when (nuevo.lowercase()) {
            "low" -> Mode.LOW
            "off" -> Mode.OFF
            else -> Mode.ON
        }
        frameIntervalMs = when (mode) {
            Mode.ON -> 33L
            Mode.LOW -> 66L
            Mode.OFF -> 0L
        }
        if (mode == Mode.OFF) {
            stop()
            visibility = GONE
        } else {
            visibility = VISIBLE
            invalidate()
            start()
        }
    }

    fun setLevel(value: Float) {
        nivel = value.coerceIn(0f, 1f)
    }

    /** Centro del orbe en coordenadas de esta vista, para alinear halo y ondas. */
    fun setFocus(x: Float, y: Float) {
        if (x <= 0f || y <= 0f) return
        if (x == focoX && y == focoY) return
        focoX = x
        focoY = y
        invalidate()
    }

    /** Onda desde el orbe al tocar o activar la wake word. */
    fun ripple() {
        rippleStartMs = SystemClock.elapsedRealtime()
        invalidate()
    }

    fun pause() {
        paused = true
        stop()
    }

    fun resume() {
        paused = false
        start()
    }

    private fun start() {
        if (running || mode == Mode.OFF || paused || !attached || !isShown) return
        running = true
        ultimoDibujoMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stop()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Viñeta: oscurece los bordes y deja respirar el centro.
        vignettePaint.shader = RadialGradient(
            w / 2f,
            h / 2f,
            max(w, h) * 0.80f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x99000000.toInt()),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.OFF) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val ahora = SystemClock.elapsedRealtime()
        comprobarFranjaHoraria(ahora)
        val t = (ahora - inicioMs) / 1000f
        val base = currentColor()
        val acento = currentAccent()
        val secundario = currentSecundario()
        val fondo = currentFondo()

        nivelSuave = if (nivel > nivelSuave) {
            nivelSuave + (nivel - nivelSuave) * 0.35f
        } else {
            nivelSuave + (nivel - nivelSuave) * 0.10f
        }

        val reposo = state == OrbView.State.READY || state == OrbView.State.WAITING
        val intensidadEstado = when (state) {
            OrbView.State.READY, OrbView.State.WAITING -> 1f
            OrbView.State.LISTENING -> 1.10f
            OrbView.State.TRANSCRIBING -> 1.05f
            OrbView.State.HEARING -> 1.20f
            OrbView.State.THINKING -> 1.15f
            OrbView.State.SPEAKING -> 1.25f
            OrbView.State.ERROR -> 1.30f
        }
        val intensidad = if (reposo) {
            intensidadEstado * paletaHora.intensidad
        } else {
            intensidadEstado
        }
        val fx = if (focoX > 0f) focoX else w / 2f
        val fy = if (focoY > 0f) focoY else h / 2f
        val maxDim = max(w, h)

        canvas.drawColor(fondo)

        // ── Aurora ────────────────────────────────────────────────────
        val nBlobs = if (mode == Mode.LOW) 2 else 4
        for (i in 0 until nBlobs) {
            val s = semillas[i]
            val cx = w * (0.5f + 0.30f * sin(t * 0.050f * s.velocidad + s.fase))
            val cy = h * (0.47f + 0.25f * cos(t * 0.041f * s.velocidad + s.fase * 1.37f))
            val resp = 1f + 0.10f * sin(t * 0.16f * s.velocidad + s.fase * 2.1f)
            val alfa = s.alfa * intensidad * (0.78f + 0.22f * sin(t * 0.12f + s.fase))
            val tono = when (i) {
                0 -> base
                1 -> secundario
                2 -> ColorUtils.blendARGB(base, secundario, 0.55f)
                else -> acento
            }
            dibujarResplandor(
                canvas,
                cx,
                cy,
                w * s.radioX * resp,
                h * s.radioY * resp,
                s.giro + t * 1.2f * s.velocidad,
                tono,
                alfa.toInt(),
                i,
            )
        }

        // ── Cortinas de aurora ───────────────────────────────────────
        if (mode == Mode.ON) {
            for (i in 0..1) {
                val fase = i * 3.1f
                val cy = h * (0.30f + 0.16f * i + 0.06f * sin(t * 0.045f + fase))
                val cx = w * (0.5f + 0.16f * sin(t * 0.026f + fase * 1.7f))
                val resp = 0.92f + 0.08f * sin(t * 0.09f + fase)
                dibujarResplandor(
                    canvas,
                    cx,
                    cy,
                    w * 0.78f * resp,
                    h * 0.060f * resp,
                    -12f + 3f * sin(t * 0.05f + fase),
                    if (i == 0) acento else base,
                    (15f * intensidad).toInt(),
                    4 + i,
                )
            }
        }

        // ── Halo detrás del orbe ─────────────────────────────────────
        val latido = 0.5f + 0.5f * sin(t * 0.33f)
        val alfaHalo = 16f + 10f * latido + 24f * nivelSuave
        dibujarResplandor(
            canvas,
            fx,
            fy,
            maxDim * 0.28f,
            maxDim * 0.28f,
            0f,
            acento,
            (alfaHalo * intensidad).toInt().coerceIn(0, 60),
            6,
        )
        if (mode == Mode.ON) {
            dibujarResplandor(
                canvas,
                fx,
                fy,
                maxDim * 0.16f,
                maxDim * 0.16f,
                0f,
                secundario,
                (14f + 8f * latido).toInt(),
                5,
            )
        }

        // ── Polvo ────────────────────────────────────────────────────
        val nPolvo = if (mode == Mode.LOW) 16 else particulas.size
        for (i in 0 until nPolvo) {
            val p = particulas[i]
            val x = p.x * w + sin(t * 0.05f * p.deriva + p.fase) * w * 0.020f
            val bruto = p.y - t * 0.0045f * p.deriva
            val y = ((bruto % 1f) + 1f) % 1f * h
            val parpadeo = 0.5f + 0.5f * sin(t * p.parpadeo + p.fase)
            val alfa = (10f + 38f * parpadeo * p.deriva).toInt()
            dustPaint.color = withAlpha(
                if (i % 4 == 0) Color.WHITE else acento,
                alfa,
            )
            canvas.drawCircle(x, y, p.tamano * densidad, dustPaint)
        }

        // ── Luciérnagas ──────────────────────────────────────────────
        val nLuces = if (mode == Mode.LOW) 3 else luciernagas.size
        for (i in 0 until nLuces) {
            val l = luciernagas[i]
            val x = w * (l.x + l.amplitud * sin(t * l.velocidad + l.fase))
            val y = h * (l.y + l.amplitud * 0.8f * cos(t * l.velocidad * 0.8f + l.fase * 1.4f))
            val pulso = 0.55f + 0.45f * sin(t * 0.35f + l.fase * 2.0f)
            luciernagaPaint.colorFilter = filtro(7, acento)
            luciernagaPaint.alpha = (28f + 60f * pulso).toInt().coerceIn(0, 110)
            val radio = l.tamano * dp(6f)
            canvas.drawBitmap(blob, null, android.graphics.RectF(x - radio, y - radio, x + radio, y + radio), luciernagaPaint)
        }

        // ── Anillos ──────────────────────────────────────────────────
        if (reposo) {
            val periodo = 7.4f
            ringPaint.strokeWidth = dp(1f)
            for (k in 0..1) {
                val p = ((t / periodo) + k * 0.5f) % 1f
                val radio = maxDim * (0.12f + p * 0.60f)
                val alfa = (sin(p * PI.toFloat()) * 15f * intensidad).toInt()
                if (alfa <= 0) continue
                ringPaint.color = withAlpha(acento, alfa)
                canvas.drawCircle(fx, fy, radio, ringPaint)
            }
        } else if (state == OrbView.State.LISTENING ||
            state == OrbView.State.HEARING ||
            state == OrbView.State.SPEAKING
        ) {
            val energia = if (state == OrbView.State.SPEAKING) {
                0.45f + 0.18f * sin(t * 4.2f)
            } else {
                0.22f + 0.38f * nivelSuave
            }
            wavePaint.strokeWidth = dp(1.1f)
            for (i in 0..1) {
                val p = (t * 0.22f + i * 0.5f) % 1f
                val radio = maxDim * (0.13f + p * 0.58f)
                val alfa = ((1f - p) * (30f + 54f * energia)).toInt()
                wavePaint.color = withAlpha(acento, alfa.coerceIn(0, 150))
                canvas.drawCircle(fx, fy, radio, wavePaint)
            }
        }

        // ── Cometa (solo en reposo) ──────────────────────────────────
        if (reposo) dibujarCometa(canvas, w, h, ahora, acento, intensidad)

        // ── Viñeta y onda de toque ───────────────────────────────────
        canvas.drawRect(0f, 0f, w, h, vignettePaint)

        if (rippleStartMs > 0L) {
            val p = (ahora - rippleStartMs) / 1100f
            if (p in 0f..1f) {
                val radio = maxDim * (0.09f + p * 0.70f)
                ripplePaint.strokeWidth = dp(1.8f) * (1f - 0.6f * p)
                ripplePaint.color = withAlpha(acento, ((1f - p) * 95f).toInt())
                canvas.drawCircle(fx, fy, radio, ripplePaint)
            } else {
                rippleStartMs = 0L
            }
        }
    }

    /**
     * Dibuja el bitmap de resplandor estirado y rotado. `ranura` identifica el
     * tinte para reutilizar el filtro de color entre fotogramas.
     */
    private fun dibujarResplandor(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radioX: Float,
        radioY: Float,
        giro: Float,
        color: Int,
        alfa: Int,
        ranura: Int,
    ) {
        if (alfa <= 0 || radioX <= 0f || radioY <= 0f) return
        auroraPaint.colorFilter = filtro(ranura, color)
        auroraPaint.alpha = alfa.coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(giro)
        canvas.scale(radioX / BLOB_RADIO, radioY / BLOB_RADIO)
        canvas.drawBitmap(blob, -BLOB_RADIO.toFloat(), -BLOB_RADIO.toFloat(), auroraPaint)
        canvas.restore()
    }

    private fun dibujarCometa(
        canvas: Canvas,
        w: Float,
        h: Float,
        ahora: Long,
        acento: Int,
        intensidad: Float,
    ) {
        if (mode != Mode.ON) return
        if (cometaInicioMs < 0L) {
            if (proximoCometaMs == 0L) {
                proximoCometaMs = ahora + 7000L + (ahora % 4000L)
            }
            if (ahora < proximoCometaMs) return
            cometaInicioMs = ahora
            cometaSemilla = (ahora / 37L).toInt()
        }
        val duracion = 1500f
        val p = (ahora - cometaInicioMs) / duracion
        if (p > 1f) {
            cometaInicioMs = -1L
            proximoCometaMs = ahora + 11000L + (cometaSemilla % 7000)
            return
        }
        val rnd = Random(cometaSemilla)
        val x0 = w * (0.04f + 0.32f * rnd.nextFloat())
        val y0 = h * (0.06f + 0.22f * rnd.nextFloat())
        val angulo = (14f + 15f * rnd.nextFloat()) * PI.toFloat() / 180f
        val largo = max(w, h) * 0.40f
        val cabezaX = x0 + cos(angulo) * largo * p
        val cabezaY = y0 + sin(angulo) * largo * p
        val cola = largo * 0.16f
        val envolvente = sin(p * PI.toFloat())
        for (k in 0 until 12) {
            val f = k / 11f
            val x = cabezaX - cos(angulo) * cola * f
            val y = cabezaY - sin(angulo) * cola * f
            val alfa = ((1f - f) * 82f * envolvente * intensidad).toInt()
            if (alfa <= 0) continue
            dustPaint.color = withAlpha(acento, alfa.coerceIn(0, 160))
            canvas.drawCircle(x, y, dp(1.5f) * (1f - 0.65f * f), dustPaint)
        }
        dustPaint.color = withAlpha(Color.WHITE, (170f * envolvente).toInt().coerceIn(0, 200))
        canvas.drawCircle(cabezaX, cabezaY, dp(1.4f), dustPaint)
    }

    private fun currentColor(): Int = ColorUtils.blendARGB(colorFrom, colorTo, colorMix)

    private fun currentAccent(): Int = ColorUtils.blendARGB(accentFrom, accentTo, colorMix)

    private fun currentSecundario(): Int =
        ColorUtils.blendARGB(secundarioFrom, secundarioTo, colorMix)

    private fun currentFondo(): Int = ColorUtils.blendARGB(fondoFrom, fondoTo, colorMix)

    /** En reposo el fondo sigue la hora; los estados activos conservan su color. */
    private fun estiloDe(estado: OrbView.State): Estilo =
        if (estado == OrbView.State.READY || estado == OrbView.State.WAITING) {
            estiloHorario()
        } else {
            estilos.getValue(estado)
        }

    private fun estiloHorario(): Estilo = Estilo(
        paletaHora.base,
        paletaHora.acento,
        paletaHora.secundario,
        paletaHora.fondo,
    )

    private fun animarHacia(estilo: Estilo, duracionMs: Long) {
        colorFrom = currentColor()
        accentFrom = currentAccent()
        secundarioFrom = currentSecundario()
        fondoFrom = currentFondo()
        colorTo = estilo.color
        accentTo = estilo.acento
        secundarioTo = estilo.secundario
        fondoTo = estilo.fondo
        colorMix = 0f
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = duracionMs
            addUpdateListener {
                colorMix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /** Revisa la hora cada 30 s; al cambiar de franja, transiciona despacio. */
    private fun comprobarFranjaHoraria(ahoraMs: Long) {
        if (ahoraMs < proximaComprobacionHoraMs) return
        proximaComprobacionHoraMs = ahoraMs + 30_000L
        val nueva = FondoHorario.paletaDe(
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        )
        if (nueva == paletaHora) return
        paletaHora = nueva
        if (state == OrbView.State.READY || state == OrbView.State.WAITING) {
            animarHacia(estiloHorario(), 1800L)
        }
    }

    private fun filtro(ranura: Int, color: Int): PorterDuffColorFilter {
        val indice = ranura.coerceIn(0, filtros.size - 1)
        if (tintes[indice] != color || filtros[indice] == null) {
            tintes[indice] = color
            filtros[indice] = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        return filtros[indice]!!
    }

    private fun crearBlob(): Bitmap {
        val lado = BLOB_RADIO * 2
        val mapa = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        pincel.shader = RadialGradient(
            BLOB_RADIO.toFloat(),
            BLOB_RADIO.toFloat(),
            BLOB_RADIO.toFloat(),
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xE6FFFFFF.toInt(),
                0x8CFFFFFF.toInt(),
                0x33FFFFFF,
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.28f, 0.58f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        Canvas(mapa).drawCircle(BLOB_RADIO.toFloat(), BLOB_RADIO.toFloat(), BLOB_RADIO.toFloat(), pincel)
        return mapa
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val BLOB_RADIO = 128
    }
}
