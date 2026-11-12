package com.cdcb.samanthapp

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Descarga e instala el modelo pequeño de Vosk para español (~39 MB).
 * Queda en filesDir/vosk/vosk-model-small-es-0.42.
 */
object VoskModelStore {
    private const val TAG = "SamanthaVosk"
    private const val URL_MODELO =
        "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val CARPETA = "vosk-model-small-es-0.42"
    private const val ARCHIVO_CLAVE = "am/final.mdl"
    private val lock = Any()

    fun carpeta(context: Context): File = File(context.filesDir, "vosk/$CARPETA")

    fun listo(context: Context): Boolean = File(carpeta(context), ARCHIVO_CLAVE).isFile

    /**
     * Descarga y descomprime el modelo si falta. [onProgress] recibe 0..99
     * durante la descarga y 100 al terminar; -1 si no hay tamaño conocido.
     * Se ejecuta en el hilo actual (llamar desde IO).
     */
    fun asegurar(context: Context, onProgress: (Int) -> Unit): File = synchronized(lock) {
        val destino = carpeta(context)
        if (File(destino, ARCHIVO_CLAVE).isFile) return destino

        val base = File(context.filesDir, "vosk").apply { mkdirs() }
        val zip = File(base, "$CARPETA.zip.part")
        val temp = File(base, "$CARPETA.tmp")
        zip.delete()
        temp.deleteRecursively()
        try {
            descargar(zip, onProgress)
            descomprimir(zip, temp)
            val extraida = File(temp, CARPETA)
            val origen = if (extraida.isDirectory) extraida else temp
            if (!File(origen, ARCHIVO_CLAVE).isFile) {
                throw IOException("El modelo descargado no tiene la estructura esperada")
            }
            destino.deleteRecursively()
            destino.parentFile?.mkdirs()
            if (origen.renameTo(destino)) {
                temp.deleteRecursively()
            } else {
                origen.copyRecursively(destino, overwrite = true)
                temp.deleteRecursively()
            }
            if (!File(destino, ARCHIVO_CLAVE).isFile) {
                throw IOException("No se pudo instalar el modelo")
            }
            onProgress(100)
            Log.i(TAG, "modelo Vosk instalado en ${destino.absolutePath}")
            destino
        } catch (e: Exception) {
            zip.delete()
            temp.deleteRecursively()
            throw e
        }
    }

    private fun descargar(destino: File, onProgress: (Int) -> Unit) {
        val conn = (URL(URL_MODELO).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SamanthApp/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            var leidos = 0L
            var ultimo = -1
            conn.inputStream.use { entrada ->
                destino.outputStream().use { salida ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        salida.write(buffer, 0, n)
                        leidos += n
                        val pct = if (total > 0) {
                            ((leidos * 100) / total).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        if (pct != ultimo) {
                            ultimo = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun descomprimir(zip: File, destino: File) {
        destino.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { entrada ->
            while (true) {
                val item = entrada.nextEntry ?: break
                val nombre = item.name.replace('\\', '/')
                if (nombre.startsWith("/") || nombre.contains("..")) {
                    entrada.closeEntry()
                    continue
                }
                val salida = File(destino, nombre)
                if (item.isDirectory) {
                    salida.mkdirs()
                } else {
                    salida.parentFile?.mkdirs()
                    salida.outputStream().use { entrada.copyTo(it) }
                }
                entrada.closeEntry()
            }
        }
    }
}

/** Modelo compartido entre la actividad y el servicio para no cargarlo dos veces. */
object VoskModelHolder {
    @Volatile
    private var modelo: Model? = null

    @Synchronized
    fun obtener(context: Context): Model {
        modelo?.let { return it }
        val nuevo = Model(VoskModelStore.carpeta(context).absolutePath)
        modelo = nuevo
        return nuevo
    }

    @Synchronized
    fun liberar() {
        modelo?.close()
        modelo = null
    }
}
