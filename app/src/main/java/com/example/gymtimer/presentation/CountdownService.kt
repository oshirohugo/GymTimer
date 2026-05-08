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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
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
        private const val FINISH_CHANNEL_ID = "gym_timer_finished"
        private const val NOTIFICATION_ID = 1
        const val FINISH_NOTIFICATION_ID = 2
        private const val TICK_MS = 30L
        // Three separate one-shots: 200 ms buzz at default amplitude, fired 300 ms apart.
        private const val PULSE_DURATION_MS = 200L
        private const val PULSE_INTERVAL_MS = 300L
        private const val PULSE_COUNT = 3

        // Process-level state so cycle progress and counter survive the
        // service being destroyed between cycles or while paused.
        @Volatile
        var remainingMs: Long = DURATION_MS
            private set

        @Volatile
        var cycleCount: Int = 0
            private set

        fun resetCycles() {
            cycleCount = 0
        }

        @Volatile
        var state: State = State.IDLE
            private set
    }

    private var timer: CountDownTimer? = null
    private lateinit var notificationManager: NotificationManager
    private var vibrator: Vibrator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
                state = State.IDLE
                remainingMs = DURATION_MS
                surfaceFinishedTimer()
                vibrate()
                // Keep the foreground service alive until the last pulse has fired,
                // otherwise the process can doze and the queued vibrations are dropped.
                val keepAliveMs = (PULSE_COUNT - 1) * PULSE_INTERVAL_MS + PULSE_DURATION_MS + 150L
                mainHandler.postDelayed({
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, keepAliveMs)
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gym Timer")
            .setContentText("$cycleCount/$MAX_CYCLES — ${formatTime(remainingMs)}")
            .setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (state == State.RUNNING) {
            val status = Status.Builder()
                .addTemplate("Gym Timer")
                .build()
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_play_arrow_24dp)
                .setTouchIntent(pending)
                .setStatus(status)
                .build()
                .apply(applicationContext)
        }

        return builder.build()
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Countdown",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // High-importance channel so the system honors the full-screen intent
        // we attach to the finish notification when the watch is locked or asleep.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FINISH_CHANNEL_ID,
                "Timer Finished",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun surfaceFinishedTimer() {
        val launchIntent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        val pending = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finishNotification = NotificationCompat.Builder(this, FINISH_CHANNEL_ID)
            .setContentTitle("Gym Timer")
            .setContentText("Round finished")
            .setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(FINISH_NOTIFICATION_ID, finishNotification)
        // Direct launch covers the unlocked / awake case; the full-screen intent
        // above covers locked / dozing. startActivity from a foreground service
        // is allowed because the service was started while the activity was visible.
        runCatching { startActivity(launchIntent) }
    }

    private fun vibrate() {
        val v = vibrator ?: return
        // Three separate one-shots feel noticeably stronger than a single waveform
        // on Wear OS — DEFAULT_AMPLITUDE goes through the strongest hardware path.
        val pulse = Runnable {
            v.vibrate(
                VibrationEffect.createOneShot(
                    PULSE_DURATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
        pulse.run()
        for (i in 1 until PULSE_COUNT) {
            mainHandler.postDelayed(pulse, i * PULSE_INTERVAL_MS)
        }
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
