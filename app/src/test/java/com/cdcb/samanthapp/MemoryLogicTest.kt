package com.cdcb.samanthapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameExtractorTest {

    @Test
    fun extraeNombreDeFrasesExplicitas() {
        assertEquals("Ana", NameExtractor.extract("me llamo Ana"))
        assertEquals("Fredy", NameExtractor.extract("Mi nombre es fredy."))
        assertEquals("Juan", NameExtractor.extract("llámame Juan"))
        assertEquals("María", NameExtractor.extract("ME LLAMO maría"))
    }

    @Test
    fun soySoloCuandoSePermite() {
        assertEquals("Juan", NameExtractor.extract("soy Juan"))
        assertNull(NameExtractor.extract("soy Juan", allowSoy = false))
    }

    @Test
    fun noConfundeFrasesComunes() {
        assertNull(NameExtractor.extract("soy de Bogotá"))
        assertNull(NameExtractor.extract("me llamo la atención"))
        assertNull(NameExtractor.extract("mi nombre es un misterio"))
        assertNull(NameExtractor.extract("hola, ¿cómo estás?"))
    }

    @Test
    fun respuestaCortaAlPreguntarElNombre() {
        assertEquals("Juan", NameExtractor.extractShortAnswer("Juan"))
        assertEquals("Juan Pablo", NameExtractor.extractShortAnswer("juan pablo"))
        assertEquals("María José", NameExtractor.extractShortAnswer("maría josé"))
        assertNull(NameExtractor.extractShortAnswer("no sé"))
        assertNull(NameExtractor.extractShortAnswer("qué"))
        assertNull(NameExtractor.extractShortAnswer("hola"))
        assertNull(NameExtractor.extractShortAnswer("una frase demasiado larga para ser un nombre"))
    }

    @Test
    fun extraeNombreEnIngles() {
        assertEquals("David", NameExtractor.extract("My name is David"))
        assertEquals("David", NameExtractor.extract("I'm David"))
        assertEquals("Ana", NameExtractor.extract("Call me Ana"))
        assertNull(NameExtractor.extract("I'm fine"))
        assertNull(NameExtractor.extract("This is my house"))
    }
}

class FactListTest {

    @Test
    fun agregaYEvitaDuplicados() {
        val facts = FactList.add(emptyList(), "  le gusta el café  ")
        assertEquals(listOf("le gusta el café"), facts)
        assertEquals(facts, FactList.add(facts, "Le gusta el café"))
    }

    @Test
    fun ignoraRecuerdosVacios() {
        assertNull(FactList.normalize("   "))
        assertEquals(emptyList<String>(), FactList.add(emptyList(), "  "))
    }

    @Test
    fun olvidaPorCoincidenciaParcial() {
        val facts = listOf("le gusta el café", "vive en Bogotá")
        val (restantes, borrados) = FactList.remove(facts, "café")
        assertEquals(1, borrados)
        assertEquals(listOf("vive en Bogotá"), restantes)
    }

    @Test
    fun limitaLaCantidadDeRecuerdos() {
        var facts = emptyList<String>()
        repeat(FactList.MAX_FACTS + 5) { facts = FactList.add(facts, "recuerdo $it") }
        assertEquals(FactList.MAX_FACTS, facts.size)
        assertEquals("recuerdo 5", facts.first())
    }
}

class MemoryCommandsTest {

    @Test
    fun detectaRecuerdaYQuitaElQue() {
        assertEquals(
            MemoryCommands.Result.Remember("me gusta el café"),
            MemoryCommands.parse("Recuerda que me gusta el café")
        )
        assertEquals(
            MemoryCommands.Result.Remember("comprar pan"),
            MemoryCommands.parse("recuerda comprar pan.")
        )
        assertEquals(
            MemoryCommands.Result.Remember("me gusta el café"),
            MemoryCommands.parse("Recuérdame que me gusta el café")
        )
        assertEquals(
            MemoryCommands.Result.Remember("tengo cita mañana"),
            MemoryCommands.parse("Acuérdate de que tengo cita mañana")
        )
        assertEquals(
            MemoryCommands.Result.Remember("mi talla es 42"),
            MemoryCommands.parse("Anota que mi talla es 42")
        )
        assertEquals(
            MemoryCommands.Result.Remember("la clave del wifi"),
            MemoryCommands.parse("Guarda en memoria que la clave del wifi")
        )
    }

    @Test
    fun detectaOlvidaEnSusVariantes() {
        assertEquals(
            MemoryCommands.Result.Forget("me gusta el café"),
            MemoryCommands.parse("olvida que me gusta el café")
        )
        assertEquals(
            MemoryCommands.Result.Forget("me gusta el café"),
            MemoryCommands.parse("Olvídate de que me gusta el café")
        )
        assertEquals(
            MemoryCommands.Result.Forget("Bogotá"),
            MemoryCommands.parse("borra de tu memoria Bogotá")
        )
    }

    @Test
    fun detectaComandosEnIngles() {
        assertEquals(
            MemoryCommands.Result.Remember("I like coffee"),
            MemoryCommands.parse("Remember that I like coffee")
        )
        assertEquals(
            MemoryCommands.Result.Remember("my size is 42"),
            MemoryCommands.parse("Note that my size is 42")
        )
        assertEquals(
            MemoryCommands.Result.Forget("I like coffee"),
            MemoryCommands.parse("Forget that I like coffee")
        )
    }

    @Test
    fun unaPreguntaNoEsUnComando() {
        assertNull(MemoryCommands.parse("¿qué recuerdas de mí?"))
        assertNull(MemoryCommands.parse("hola Samantha"))
    }
}

class HistoryRulesTest {

    @Test
    fun quitaElUltimoIntercambioCompleto() {
        val historial = listOf(
            Message("user", "hola"),
            Message("assistant", "hola, ¿cómo estás?"),
            Message("user", "cuéntame algo"),
            Message("assistant", "había una vez..."),
        )
        assertEquals(
            listOf(
                Message("user", "hola"),
                Message("assistant", "hola, ¿cómo estás?"),
            ),
            HistoryRules.dropLastExchange(historial)
        )
    }

    @Test
    fun noTocaUnHistorialQueNoTerminaEnRespuesta() {
        val historial = listOf(Message("user", "hola"))
        assertEquals(historial, HistoryRules.dropLastExchange(historial))
        assertEquals(emptyList<Message>(), HistoryRules.dropLastExchange(emptyList()))
    }

    @Test
    fun quitaSoloLaRespuestaSiNoHayUsuarioAntes() {
        val historial = listOf(Message("assistant", "hola"))
        assertEquals(emptyList<Message>(), HistoryRules.dropLastExchange(historial))
    }
}
