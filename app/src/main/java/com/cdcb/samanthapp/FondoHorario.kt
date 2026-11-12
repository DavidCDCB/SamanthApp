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
 * - Mañana (06:00–11:59): verde agua y dorado, como un amanecer fresco.
 * - Mediodía (12:00–15:59): azul cielo y cian, luz clara.
 * - Tarde (16:00–19:59): naranja, rosa y violeta, atardecer.
 * - Noche (20:00–05:59): índigo y violeta, profundo y tenue.
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
            base = 0xFF2DD4BF.toInt(),
            acento = 0xFFFBBF24.toInt(),
            secundario = 0xFFFB7185.toInt(),
            fondo = 0xFF06110F.toInt(),
            intensidad = 1.00f,
        )
        FranjaHoraria.MEDIODIA -> PaletaHoraria(
            base = 0xFF0EA5E9.toInt(),
            acento = 0xFF67E8F9.toInt(),
            secundario = 0xFFA5B4FC.toInt(),
            fondo = 0xFF04101C.toInt(),
            intensidad = 1.08f,
        )
        FranjaHoraria.TARDE -> PaletaHoraria(
            base = 0xFFFB923C.toInt(),
            acento = 0xFFF472B6.toInt(),
            secundario = 0xFFA78BFA.toInt(),
            fondo = 0xFF160A0C.toInt(),
            intensidad = 1.05f,
        )
        FranjaHoraria.NOCHE -> PaletaHoraria(
            base = 0xFF4F46E5.toInt(),
            acento = 0xFF22D3EE.toInt(),
            secundario = 0xFF8B5CF6.toInt(),
            fondo = 0xFF04060F.toInt(),
            intensidad = 0.92f,
        )
    }

    fun paletaDe(hora: Int): PaletaHoraria = paleta(franja(hora))
}
