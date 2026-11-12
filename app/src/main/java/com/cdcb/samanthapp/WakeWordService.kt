package com.cdcb.samanthapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Servicio en primer plano para «Hola Samantha» cuando la app no está visible.
 *
 * Android 14+ prohíbe arrancar un servicio de micrófono desde segundo plano,
 * así que la actividad lo inicia mientras está visible y lo deja en pausa.
 * Al salir de la app se le envía ACTION_RESUME y empieza a escuchar; al volver,
 * ACTION_PAUSE. La notificación permanente es obligatoria en este modo.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: WakeWordEngine? = null
    private var modeloListo = false
    private var debeEscuchar = false

    override fun onCreate() {
        super.onCreate()
        crearCanal()
        try {
            startForeground(NOTIF_ID, notificacion(activa = false))
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo iniciar el servicio en primer plano: ${e.message}")
            stopSelf()
            return
        }
        running = true
        if (!WakeWordSettings.enabled(this) || !WakeWordSettings.background(this)) {
            stopSelf()
            return
        }
        val nuevo = WakeWordEngine(
            this,
            onWake = { despertar() },
            onError = { Log.w(TAG, it) }
        )
        engine = nuevo
        scope.launch {
            val ok = withContext(Dispatchers.IO) { nuevo.cargar() }
            if (!ok) {
                Log.w(TAG, "modelo no disponible para el servicio")
                stopSelf()
                return@launch
            }
            modeloListo = true
            if (engine === nuevo && debeEscuchar) nuevo.iniciar()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WakeWordSettings.enabled(this) || !WakeWordSettings.background(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RESUME -> {
                debeEscuchar = true
                actualizarNotificacion(activa = true)
                if (modeloListo) engine?.iniciar()
            }
            ACTION_PAUSE -> {
                debeEscuchar = false
                engine?.detener()
                actualizarNotificacion(activa = false)
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Arranque desde la actividad: queda en espera hasta que la
                // app pase a segundo plano.
                debeEscuchar = false
                engine?.detener()
                actualizarNotificacion(activa = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun despertar() {
        Log.i(TAG, "wake word detectada en segundo plano")
        // Android 14+ bloquea abrir actividades desde un servicio, así que
        // se muestra un aviso de alta prioridad; al tocarlo se abre la app.
        mostrarAlerta()
        stopSelf()
    }

    private fun mostrarAlerta() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val abrir = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(WakeWordSettings.EXTRA_WAKE_WORD, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            1,
            abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alerta = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.wake_word_alert_title))
            .setContentText(getString(R.string.wake_word_alert_text))
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_mic, getString(R.string.wake_word_alert_action), pending)
            .build()
        manager.notify(ALERT_ID, alerta)
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val canal = NotificationChannel(
            CANAL,
            getString(R.string.wake_word_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.wake_word_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(canal)
        val alerta = NotificationChannel(
            ALERT_CHANNEL,
            getString(R.string.wake_word_alert_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.wake_word_alert_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(alerta)
    }

    private fun actualizarNotificacion(activa: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID, notificacion(activa))
    }

    private fun notificacion(activa: Boolean): Notification {
        val abrir = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titulo = if (activa) {
            R.string.wake_word_notification_title
        } else {
            R.string.wake_word_notification_paused_title
        }
        val texto = if (activa) {
            R.string.wake_word_notification_text
        } else {
            R.string.wake_word_notification_paused_text
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(titulo))
            .setContentText(getString(texto))
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        running = false
        engine?.detener()
        engine?.liberar()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SamanthaVosk"
        private const val CANAL = "samantha_wake_word"
        private const val ALERT_CHANNEL = "samantha_wake_alert"
        private const val NOTIF_ID = 41
        const val ALERT_ID = 42

        const val ACTION_RESUME = "com.cdcb.samanthapp.action.WAKE_RESUME"
        const val ACTION_PAUSE = "com.cdcb.samanthapp.action.WAKE_PAUSE"
        const val ACTION_STOP = "com.cdcb.samanthapp.action.WAKE_STOP"

        @Volatile
        var running = false
            private set
    }
}
