package com.cdcb.samanthapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FondoHorarioTest {

    @Test
    fun `las franjas cubren el dia completo`() {
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(0))
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(5))
        assertEquals(FranjaHoraria.MANANA, FondoHorario.franja(6))
        assertEquals(FranjaHoraria.MANANA, FondoHorario.franja(11))
        assertEquals(FranjaHoraria.MEDIODIA, FondoHorario.franja(12))
        assertEquals(FranjaHoraria.MEDIODIA, FondoHorario.franja(15))
        assertEquals(FranjaHoraria.TARDE, FondoHorario.franja(16))
        assertEquals(FranjaHoraria.TARDE, FondoHorario.franja(19))
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(20))
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(23))
    }

    @Test
    fun `las horas fuera de rango no rompen la franja`() {
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(-1))
        assertEquals(FranjaHoraria.NOCHE, FondoHorario.franja(24))
    }

    @Test
    fun `paletaDe usa la franja correcta`() {
        assertEquals(
            FondoHorario.paleta(FranjaHoraria.MANANA),
            FondoHorario.paletaDe(9),
        )
        assertEquals(
            FondoHorario.paleta(FranjaHoraria.TARDE),
            FondoHorario.paletaDe(18),
        )
    }

    @Test
    fun `las cuatro paletas son diferentes`() {
        val paletas = FranjaHoraria.values().map { FondoHorario.paleta(it) }
        assertEquals(4, paletas.toSet().size)
    }

    @Test
    fun `los fondos son oscuros para cuidar el contraste`() {
        FranjaHoraria.values().forEach { franja ->
            val fondo = FondoHorario.paleta(franja).fondo
            assertTrue(
                "El fondo de $franja es demasiado claro: ${luminancia(fondo)}",
                luminancia(fondo) < 32.0,
            )
        }
    }

    private fun luminancia(color: Int): Double {
        val rojo = (color shr 16) and 0xFF
        val verde = (color shr 8) and 0xFF
        val azul = color and 0xFF
        return rojo * 0.2126 + verde * 0.7152 + azul * 0.0722
    }
}
