package com.cdcb.samanthapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {

    @Test
    fun aceptaLaFraseConAcentosYSignos() {
        assertTrue(WakeWordMatcher.esHolaSamantha("hola samantha"))
        assertTrue(WakeWordMatcher.esHolaSamantha("¡Hola, Samanta!"))
        assertTrue(WakeWordMatcher.esHolaSamantha("Oye Samantha"))
        assertTrue(WakeWordMatcher.esHolaSamantha("ola samantha"))
    }

    @Test
    fun rechazaFrasesSinSaludoOSinNombre() {
        assertFalse(WakeWordMatcher.esHolaSamantha("hola"))
        assertFalse(WakeWordMatcher.esHolaSamantha("samantha"))
        assertFalse(WakeWordMatcher.esHolaSamantha("hola, ¿cómo estás?"))
        assertFalse(WakeWordMatcher.esHolaSamantha(""))
    }

    @Test
    fun toleraErroresPequenosDelReconocedor() {
        assertTrue(WakeWordMatcher.esHolaSamantha("hola samanta"))
        assertTrue(WakeWordMatcher.esHolaSamantha("hola samanth"))
        assertTrue(WakeWordMatcher.similitud("samantha", "samanta") > 0.8)
        assertEquals(1.0, WakeWordMatcher.similitud("samantha", "samantha"), 0.001)
    }
}
