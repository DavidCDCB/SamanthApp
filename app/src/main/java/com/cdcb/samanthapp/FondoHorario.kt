package com.cdcb.samanthapp

/** Paleta del fondo para una franja del día. */
data class PaletaHoraria(
    val base: Int,
    val acento: Int,
    val secundario: Int,
    val fondo: Int,
    val intensidad: Float,
)

/** Franjas del día que cambian el color del fondo. */
enum class FranjaHoraria {
    MANANA,
    MEDIODIA,
    TARDE,
    NOCHE,
}

/**
 * Elige el color del fondo según la hora local del teléfono.
 *
 * - Mañana (06:00–11:59): amarillo y dorado.
 * - Mediodía (12:00–15:59): rojo y coral.
 * - Tarde (16:00–19:59): verde y esmeralda.
 * - Noche (20:00–05:59): morado y violeta.
 *
 * Solo aplica al fondo en reposo; los estados activos (escuchar, pensar,
 * hablar, error) conservan su color funcional.
 */
object FondoHorario {

    fun franja(hora: Int): FranjaHoraria = when (hora.coerceIn(0, 23)) {
        in 6..11 -> FranjaHoraria.MANANA
        in 12..15 -> FranjaHoraria.MEDIODIA
        in 16..19 -> FranjaHoraria.TARDE
        else -> FranjaHoraria.NOCHE
    }

    fun paleta(franja: FranjaHoraria): PaletaHoraria = when (franja) {
        FranjaHoraria.MANANA -> PaletaHoraria(
            base = 0xFFFACC15.toInt(),
            acento = 0xFFFEF08A.toInt(),
            secundario = 0xFFFB923C.toInt(),
            fondo = 0xFF0F0D04.toInt(),
            intensidad = 1.00f,
        )
        FranjaHoraria.MEDIODIA -> PaletaHoraria(
            base = 0xFFEF4444.toInt(),
            acento = 0xFFFCA5A5.toInt(),
            secundario = 0xFFF97316.toInt(),
            fondo = 0xFF150506.toInt(),
            intensidad = 1.00f,
        )
        FranjaHoraria.TARDE -> PaletaHoraria(
            base = 0xFF22C55E.toInt(),
            acento = 0xFF86EFAC.toInt(),
            secundario = 0xFF14B8A6.toInt(),
            fondo = 0xFF04120A.toInt(),
            intensidad = 1.00f,
        )
        FranjaHoraria.NOCHE -> PaletaHoraria(
            base = 0xFFA855F7.toInt(),
            acento = 0xFFD8B4FE.toInt(),
            secundario = 0xFF6366F1.toInt(),
            fondo = 0xFF0A0412.toInt(),
            intensidad = 0.95f,
        )
    }

    fun paletaDe(hora: Int): PaletaHoraria = paleta(franja(hora))
}
