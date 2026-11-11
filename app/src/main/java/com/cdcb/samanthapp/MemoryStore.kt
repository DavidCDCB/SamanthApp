package com.cdcb.samanthapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reglas puras de la lista de recuerdos (probadas en JVM). */
object FactList {
    const val MAX_FACTS = 40
    private const val MAX_LEN = 240

    fun normalize(raw: String): String? {
        val limpio = raw.trim()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ',', ';')
            .take(MAX_LEN)
        return limpio.ifBlank { null }
    }

    fun add(facts: List<String>, raw: String): List<String> {
        val nuevo = normalize(raw) ?: return facts
        if (facts.any { it.equals(nuevo, ignoreCase = true) }) return facts
        val salida = facts + nuevo
        return if (salida.size > MAX_FACTS) salida.takeLast(MAX_FACTS) else salida
    }

    fun remove(facts: List<String>, query: String): Pair<List<String>, Int> {
        val q = normalize(query) ?: return facts to 0
        val restantes = facts.filterNot { it.contains(q, ignoreCase = true) }
        return restantes to (facts.size - restantes.size)
    }
}

/**
 * Memoria persistente de la app: nombre del usuario, recuerdos y últimos
 * turnos de conversación. Vive en SharedPreferences privadas.
 */
class MemoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("samantha_memory", Context.MODE_PRIVATE)

    var userName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim().take(40)).apply()
        }

    fun facts(): List<String> = leerLista(KEY_FACTS)

    fun addFact(raw: String): List<String> {
        val lista = FactList.add(facts(), raw)
        guardarLista(KEY_FACTS, lista)
        return lista
    }

    fun forget(query: String): Pair<List<String>, Int> {
        val (restantes, borrados) = FactList.remove(facts(), query)
        guardarLista(KEY_FACTS, restantes)
        return restantes to borrados
    }

    fun history(): MutableList<Message> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = obj.optString("role")
                val content = obj.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    Message(role, content)
                } else {
                    null
                }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveHistory(messages: List<Message>) {
        val arr = JSONArray()
        messages.takeLast(MAX_HISTORY).forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearMemory() {
        prefs.edit()
            .remove(KEY_FACTS)
            .remove(KEY_HISTORY)
            .remove(KEY_NAME)
            .apply()
    }

    private fun leerLista(key: String): List<String> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun guardarLista(key: String, items: List<String>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val KEY_NAME = "user_name"
        private const val KEY_FACTS = "facts"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 8
    }
}
