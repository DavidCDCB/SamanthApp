package com.cdcb.samanthapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaTest {

    @Test
    fun elAssetExisteYEsLaPersonaDeSamantha() {
        val archivo = File("src/main/assets/persona_samantha.md")
        assertTrue("falta persona_samantha.md", archivo.exists())
        val texto = archivo.readText()
        assertTrue(texto.contains("Eres Samantha"))
        assertTrue(texto.contains("Todo es por voz"))
        assertTrue(texto.contains("Cómo cuidas a la persona"))
        assertTrue(texto.contains("## Idioma"))
        assertTrue(texto.contains("mismo idioma"))
        assertFalse("no debe tener nombres quemados", texto.contains("Fredy", true))
        assertFalse("no debe prometer herramientas web", texto.contains("web_search"))
        assertFalse(texto.contains("open_search"))
    }

    @Test
    fun componePersonaFechaNombreYMemoria() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "PERSONA BASE",
            userName = "Ana",
            facts = listOf("le gusta el café", "vive en Bogotá"),
            fecha = "sábado 8 de noviembre de 2026, 20:05",
        )
        assertTrue(prompt.contains("PERSONA BASE"))
        assertTrue(prompt.contains("Ana"))
        assertTrue(prompt.contains("le gusta el café"))
        assertTrue(prompt.contains("vive en Bogotá"))
        assertTrue(prompt.contains("## Fecha y hora actuales"))
        assertTrue(prompt.contains("sábado 8 de noviembre de 2026, 20:05"))
        assertTrue(prompt.contains("## Recuerdos guardados"))
    }

    @Test
    fun sinNombrePideNoInventar() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "P",
            userName = "",
            facts = emptyList(),
            fecha = "hoy",
        )
        assertTrue(prompt.contains("No lo inventes", ignoreCase = true))
        assertTrue(prompt.contains("No tienes recuerdos guardados"))
    }

    @Test
    fun pideElNombreAlInicioCuandoNoLoSabe() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "P",
            userName = "",
            facts = emptyList(),
            fecha = "hoy",
            askName = true,
        )
        assertTrue(prompt.contains("cómo se llama", ignoreCase = true))
        assertTrue(prompt.contains("una sola vez", ignoreCase = true))
    }

    @Test
    fun conNombreNoPidePreguntarlo() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "P",
            userName = "Ana",
            facts = emptyList(),
            fecha = "hoy",
            askName = false,
        )
        assertTrue(prompt.contains("Ana"))
        assertFalse(prompt.contains("pregúntale cómo se llama", ignoreCase = true))
    }

    @Test
    fun modoInglesPideResponderEnIngles() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "P",
            userName = "David",
            facts = listOf("likes coffee"),
            fecha = "Wednesday, November 11, 2026, 22:00",
            languageMode = "en",
        )
        assertTrue(prompt.contains("mismo idioma", ignoreCase = true))
        assertTrue(prompt.contains("inglés natural", ignoreCase = true))
        assertTrue(prompt.contains("usa inglés", ignoreCase = true))
    }

    @Test
    fun modoEspanolMantieneEspanolComoDefecto() {
        val prompt = SamanthaApi.composeSystemPrompt(
            persona = "P",
            userName = "",
            facts = emptyList(),
            fecha = "hoy",
            languageMode = "es",
        )
        assertTrue(prompt.contains("mismo idioma", ignoreCase = true))
        assertTrue(prompt.contains("usa español", ignoreCase = true))
        assertFalse(prompt.contains("Responde siempre en inglés", ignoreCase = true))
    }
}
