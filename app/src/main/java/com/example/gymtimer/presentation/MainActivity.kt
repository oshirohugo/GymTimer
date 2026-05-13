package com.example.gymtimer.presentation

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.gymtimer.R
import com.google.android.material.button.MaterialButton

class MainActivity : ComponentActivity() {

    private lateinit var clockTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var roundNumberTextView: TextView
    private lateinit var primaryButton: MaterialButton
    private lateinit var pausedButtonRow: LinearLayout
    private lateinit var resetButton: MaterialButton
    private lateinit var resumeButton: MaterialButton

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshUi = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 30L)
        }
    }

    private var previousState: CountdownService.State? = null
    private var splitOffsetPx: Float = 0f

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* timer still works without notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_countdown)

        clockTextView = findViewById(R.id.clockTextView)
        countdownTextView = findViewById(R.id.countdownTextView)
        roundNumberTextView = findViewById(R.id.roundNumberTextView)
        primaryButton = findViewById(R.id.primaryButton)
        pausedButtonRow = findViewById(R.id.pausedButtonRow)
        resetButton = findViewById(R.id.resetButton)
        resumeButton = findViewById(R.id.resumeButton)

        // Distance from each paused button's natural position to the centerline.
        splitOffsetPx = 40f * resources.displayMetrics.density

        primaryButton.setOnClickListener { onPrimaryClicked() }
        resetButton.setOnClickListener { send(CountdownService.ACTION_RESET, foreground = false) }
        resumeButton.setOnClickListener { send(CountdownService.ACTION_RESUME, foreground = true) }
        roundNumberTextView.setOnClickListener {
            CountdownService.resetCycles()
            roundNumberTextView.text = "0"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onPrimaryClicked() {
        when (CountdownService.state) {
            CountdownService.State.IDLE -> send(CountdownService.ACTION_START, foreground = true)
            CountdownService.State.RUNNING -> send(CountdownService.ACTION_PAUSE, foreground = false)
            CountdownService.State.PAUSED -> { /* button hidden */ }
        }
    }

    private fun send(action: String, foreground: Boolean) {
        val intent = Intent(this, CountdownService::class.java).setAction(action)
        if (foreground) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshUi)
        handler.post(refreshUi)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(clockReceiver, filter)
        updateClock()

        getSystemService(NotificationManager::class.java)
            ?.cancel(CountdownService.FINISH_NOTIFICATION_ID)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshUi)
        unregisterReceiver(clockReceiver)
    }

    private fun updateClock() {
        clockTextView.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun updateUi() {
        val state = CountdownService.state
        countdownTextView.text = formatTime(CountdownService.remainingMs)
        roundNumberTextView.text = CountdownService.cycleCount.toString()

        // Match Samsung's stopwatch: keep the watch awake only while the
        // countdown is actively running.
        if (state == CountdownService.State.RUNNING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val prev = previousState
        when {
            prev == null -> applyStateImmediate(state)
            prev != state -> animateStateChange(prev, state)
        }
        previousState = state
    }

    private fun applyStateImmediate(state: CountdownService.State) {
        when (state) {
            CountdownService.State.IDLE -> {
                resetPrimary(running = false)
                primaryButton.visibility = View.VISIBLE
                pausedButtonRow.visibility = View.GONE
            }
            CountdownService.State.RUNNING -> {
                resetPrimary(running = true)
                primaryButton.visibility = View.VISIBLE
                pausedButtonRow.visibility = View.GONE
            }
            CountdownService.State.PAUSED -> {
                primaryButton.visibility = View.GONE
                pausedButtonRow.visibility = View.VISIBLE
                resetPausedRow()
            }
        }
    }

    private fun animateStateChange(from: CountdownService.State, to: CountdownService.State) {
        when {
            from == CountdownService.State.PAUSED -> mergeFromPaused(to)
            to == CountdownService.State.PAUSED -> splitToPaused()
            else -> morphPrimary(to)
        }
    }

    private fun resetPrimary(running: Boolean) {
        primaryButton.text = if (running) getString(R.string.button_stop) else getString(R.string.button_start)
        primaryButton.backgroundTintList = ColorStateList.valueOf(
            getColor(if (running) R.color.button_red else R.color.button_blue)
        )
        primaryButton.alpha = 1f
        primaryButton.scaleX = 1f
        primaryButton.scaleY = 1f
    }

    private fun resetPausedRow() {
        resetButton.alpha = 1f
        resetButton.translationX = 0f
        resumeButton.alpha = 1f
        resumeButton.translationX = 0f
    }

    private fun morphPrimary(to: CountdownService.State) {
        // IDLE <-> RUNNING: color crossfades, text swaps, brief scale pulse.
        val running = to == CountdownService.State.RUNNING
        val targetColor = getColor(if (running) R.color.button_red else R.color.button_blue)
        val currentColor = primaryButton.backgroundTintList?.defaultColor ?: targetColor

        primaryButton.text = if (running) getString(R.string.button_stop) else getString(R.string.button_start)

        ValueAnimator.ofArgb(currentColor, targetColor).apply {
            duration = 280L
            addUpdateListener {
                primaryButton.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int)
            }
            start()
        }
        primaryButton.animate()
            .scaleX(0.92f).scaleY(0.92f)
            .setDuration(140)
            .withEndAction {
                primaryButton.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()
    }

    private fun splitToPaused() {
        // Primary shrinks + fades out. Reset/Resume start stacked at center, slide apart and fade in.
        pausedButtonRow.visibility = View.VISIBLE
        resetButton.alpha = 0f
        resetButton.translationX = splitOffsetPx
        resumeButton.alpha = 0f
        resumeButton.translationX = -splitOffsetPx

        val ease = DecelerateInterpolator()
        resetButton.animate()
            .translationX(0f).alpha(1f)
            .setInterpolator(ease)
            .setDuration(300)
            .start()
        resumeButton.animate()
            .translationX(0f).alpha(1f)
            .setInterpolator(ease)
            .setDuration(300)
            .start()

        primaryButton.animate()
            .alpha(0f).scaleX(0.4f).scaleY(0.4f)
            .setDuration(180)
            .withEndAction {
                primaryButton.visibility = View.GONE
                primaryButton.alpha = 1f
                primaryButton.scaleX = 1f
                primaryButton.scaleY = 1f
            }
            .start()
    }

    private fun mergeFromPaused(to: CountdownService.State) {
        // Reset/Resume slide back to center and fade. Primary fades in with target text/color.
        val running = to == CountdownService.State.RUNNING
        primaryButton.text = if (running) getString(R.string.button_stop) else getString(R.string.button_start)
        primaryButton.backgroundTintList = ColorStateList.valueOf(
            getColor(if (running) R.color.button_red else R.color.button_blue)
        )
        primaryButton.visibility = View.VISIBLE
        primaryButton.alpha = 0f
        primaryButton.scaleX = 0.4f
        primaryButton.scaleY = 0.4f

        primaryButton.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(80)
            .setDuration(280)
            .start()

        resetButton.animate()
            .translationX(splitOffsetPx).alpha(0f)
            .setDuration(220)
            .start()
        resumeButton.animate()
            .translationX(-splitOffsetPx).alpha(0f)
            .setDuration(220)
            .withEndAction {
                pausedButtonRow.visibility = View.GONE
                resetPausedRow()
            }
            .start()
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (safe % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }
}
