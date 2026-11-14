package com.cdcb.samanthapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Envuelve SpeechRecognizer y su RecognitionListener. MainActivity conserva
 * las decisiones de UI, memoria y errores a través de callbacks.
 */
class SpeechController(
    private val context: Context,
    private val languageMode: () -> String,
    private val onReady: () -> Unit,
    private val onRms: (Float) -> Unit,
    private val onEndVoice: () -> Unit,
    private val onError: (error: Int, cancelled: Boolean, automatic: Boolean) -> Unit,
    private val onResults: (String) -> Unit,
    private val onPartialResults: (String) -> Unit,
) {

    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var automaticTurn = false

    fun start(automatic: Boolean = false) {
        automaticTurn = automatic
        stopping = false
        recognizer?.startListening(recognizerIntent())
    }

    fun stopListening() {
        stopping = true
        recognizer?.stopListening()
    }

    fun cancel() {
        stopping = true
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val codigo = if (languageMode() == "en") "en-US" else "es-CO"
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, codigo)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, codigo)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            stopping = false
            onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            onRms(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            onEndVoice()
        }

        override fun onError(error: Int) {
            val cancelled = stopping
            val automatic = automaticTurn
            stopping = false
            automaticTurn = false
            onError(error, cancelled, automatic)
        }

        override fun onResults(results: Bundle?) {
            stopping = false
            automaticTurn = false
            val texto = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            onResults(texto)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            onPartialResults(
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }
}
