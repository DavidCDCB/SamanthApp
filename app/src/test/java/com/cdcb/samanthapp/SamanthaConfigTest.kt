package com.cdcb.samanthapp

import org.junit.Assert.assertEquals
import org.junit.Test

class SamanthaConfigTest {

    @Test
    fun `lee reasoning_effort high y el resto de la config`() {
        val cfg = SamanthaConfig.parse(
            """
            # comentario
            ai:
              base_url: "https://opencode.ai/inference/go/openai/v1"
              model: "minimax-m3.1-flash"
              reasoning_effort: "high"   # más razonamiento
              max_tokens: 1200
              temperature: 0.4
            """.trimIndent()
        )
        assertEquals("https://opencode.ai/inference/go/openai/v1", cfg.baseUrl)
        assertEquals("minimax-m3.1-flash", cfg.model)
        assertEquals("high", cfg.reasoningEffort)
        assertEquals(1200, cfg.maxTokens)
        assertEquals(0.4, cfg.temperature, 0.0001)
    }

    @Test
    fun `sin claves usa los valores por defecto`() {
        val cfg = SamanthaConfig.parse("")
        assertEquals(SamanthaConfig.DEFAULT_BASE_URL, cfg.baseUrl)
        assertEquals(SamanthaConfig.DEFAULT_MODEL, cfg.model)
        assertEquals("high", cfg.reasoningEffort)
        assertEquals(800, cfg.maxTokens)
        assertEquals(0.6, cfg.temperature, 0.0001)
    }

    @Test
    fun `reasoning_effort vacio se conserva para omitirlo`() {
        val cfg = SamanthaConfig.parse(
            """
            ai:
              reasoning_effort: ""
            """.trimIndent()
        )
        assertEquals("", cfg.reasoningEffort)
    }

    @Test
    fun `lee la seccion firecrawl`() {
        val cfg = SamanthaConfig.parse(
            """
            firecrawl:
              api_key: "fc-prueba"
              max_results: 7
              timeout: 20
              lang: "en"
              country: "US"
            """.trimIndent()
        )
        assertEquals("fc-prueba", cfg.firecrawlApiKey)
        assertEquals(7, cfg.firecrawlMaxResults)
        assertEquals(20_000, cfg.firecrawlTimeoutMs)
        assertEquals("en", cfg.firecrawlLang)
        assertEquals("US", cfg.firecrawlCountry)
    }

    @Test
    fun `firecrawl sin config queda desactivado`() {
        val cfg = SamanthaConfig.parse("")
        assertEquals("", cfg.firecrawlApiKey)
        assertEquals(5, cfg.firecrawlMaxResults)
        assertEquals(15_000, cfg.firecrawlTimeoutMs)
    }

    @Test
    fun `lee la ventana de memoria`() {
        val cfg = SamanthaConfig.parse(
            """
            memory:
              recent_window: 20
            """.trimIndent()
        )
        assertEquals(20, cfg.historyLimit)
    }

    @Test
    fun `ventana de memoria invalida usa 8`() {
        val cfg = SamanthaConfig.parse(
            """
            memory:
              recent_window: 1
            """.trimIndent()
        )
        assertEquals(8, cfg.historyLimit)
    }

    @Test
    fun `valores invalidos no rompen la carga`() {
        val cfg = SamanthaConfig.parse(
            """
            ai:
              max_tokens: muchas
              temperature: 9
              reasoning_effort: HIGH
            """.trimIndent()
        )
        assertEquals(800, cfg.maxTokens)
        assertEquals(2.0, cfg.temperature, 0.0001)
        assertEquals("high", cfg.reasoningEffort)
    }

    @Test
    fun `ignora secciones que no son ai`() {
        val cfg = SamanthaConfig.parse(
            """
            otras:
              model: "no-deberia-usarse"
              max_tokens: 10
            ai:
              model: "minimax-m3.1-flash"
            """.trimIndent()
        )
        assertEquals("minimax-m3.1-flash", cfg.model)
        assertEquals(800, cfg.maxTokens)
    }
}
