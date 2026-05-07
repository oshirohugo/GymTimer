package com.example.gymtimer.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.gymtimer.R

class CountdownService : Service() {

    enum class State { IDLE, RUNNING, PAUSED }

    companion object {
        const val ACTION_START = "com.example.gymtimer.START"
        const val ACTION_PAUSE = "com.example.gymtimer.PAUSE"
        const val ACTION_RESUME = "com.example.gymtimer.RESUME"
        const val ACTION_RESET = "com.example.gymtimer.RESET"
        const val DURATION_MS = 60_000L
        const val MAX_CYCLES = 2

        private const val CHANNEL_ID = "gym_timer_countdown"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30L
        private const val VIBRATION_MS = 500L

        // Process-level state so cycle progress and counter survive the
        // service being destroyed between cycles or while paused.
        @Volatile
        var remainingMs: Long = DURATION_MS
            private set

        @Volatile
        var cycleCount: Int = 0
            private set

        @Volatile
        var state: State = State.IDLE
            private set
    }

    private var timer: CountDownTimer? = null
    private lateinit var notificationManager: NotificationManager
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_RESET -> handleReset()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        timer?.cancel()
        if (cycleCount >= MAX_CYCLES) {
            cycleCount = 0
        }
        remainingMs = DURATION_MS
        state = State.RUNNING
        startForegroundCompat(buildNotification())
        startTimer(DURATION_MS)
    }

    private fun handleResume() {
        timer?.cancel()
        if (remainingMs <= 0L) {
            handleStart()
            return
        }
        state = State.RUNNING
        startForegroundCompat(buildNotification())
        startTimer(remainingMs)
    }

    private fun handlePause() {
        timer?.cancel()
        timer = null
        state = State.PAUSED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleReset() {
        timer?.cancel()
        timer = null
        remainingMs = DURATION_MS
        state = State.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer(durationMs: Long) {
        timer = object : CountDownTimer(durationMs, TICK_MS) {
            override fun onTick(ms: Long) {
                remainingMs = ms
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }

            override fun onFinish() {
                timer = null
                cycleCount++
                vibrate()
                state = State.IDLE
                remainingMs = DURATION_MS
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gym Timer")
            .setContentText("$cycleCount/$MAX_CYCLES — ${formatTime(remainingMs)}")
            .setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Countdown",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun vibrate() {
        vibrator?.vibrate(
            VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (safe % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
