package com.cdcb.samanthapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExtractorTest {

    @Test
    fun extraeElArticuloYQuitaMenuScriptsYPie() {
        val html = """
            <html>
              <head><title>Noticia</title><script>alert('no')</script></head>
              <body>
                <nav>Menú de navegación</nav>
                <article>
                  <h1>Título de la noticia</h1>
                  <p>Primer párrafo con los datos importantes.</p>
                  <p>Segundo párrafo con el contexto.</p>
                </article>
                <div class="cookie-banner">Aceptar cookies</div>
                <footer>Pie de página</footer>
              </body>
            </html>
        """.trimIndent()

        val texto = HtmlExtractor.extraerTexto(html)

        assertTrue(texto.contains("Primer párrafo con los datos importantes."))
        assertTrue(texto.contains("Segundo párrafo con el contexto."))
        assertFalse(texto.contains("Menú de navegación"))
        assertFalse(texto.contains("Aceptar cookies"))
        assertFalse(texto.contains("Pie de página"))
        assertFalse(texto.contains("alert"))
    }

    @Test
    fun recortaElContenidoAlLimiteDeLaPagina() {
        val html = "<html><body><article><p>" +
            "palabra ".repeat(2_000) +
            "</p></article></body></html>"

        val texto = HtmlExtractor.extraerTexto(html)

        assertTrue(texto.length <= HtmlExtractor.MAX_PAGE_CHARS + 1)
        assertTrue(texto.endsWith("…"))
    }
}