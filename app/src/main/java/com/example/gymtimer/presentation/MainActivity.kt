/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.gymtimer.presentation

import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.gymtimer.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var countdownTextView: TextView
    private lateinit var roundNumberTextView: TextView // Reference to the new TextView\
    private lateinit var startButton: FloatingActionButton // Add this line
    private var countDownTimer: CountDownTimer? = null
    private var vibrator: Vibrator? = null // Declare Vibrator
    private var currentRound = 1 // Variable to track the current round

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContentView(R.layout.activity_countdown)

        countdownTextView = findViewById(R.id.countdownTextView)
        roundNumberTextView = findViewById(R.id.roundNumberTextView)
        startButton = findViewById(R.id.startButton)

        // Set an OnClickListener on the button
        startButton.setOnClickListener {
            startCountdown()
        }

        vibrator = getSystemService(Vibrator::class.java) // Get Vibrator using the modern way
        updateRoundNumber() // Set the initial round number

    }

    private fun startCountdown() {
        val totalMillisInFuture: Long = 60000L // 1 minute in milliseconds
        val countDownInterval: Long = 10L // Update every 10 milliseconds for hundredths

        countDownTimer = object : CountDownTimer(totalMillisInFuture, countDownInterval) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % TimeUnit.MINUTES.toSeconds(1)
                // Calculate hundredths of a second
                val hundredths = (millisUntilFinished % TimeUnit.SECONDS.toMillis(1)) / 10

                // Format the time string in MM:SS.ff format
                val timeString = String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
                countdownTextView.text = timeString
            }

            override fun onFinish() {
                countdownTextView.text = "01:00.00"
                vibrateOnFinish()
                currentRound++
                updateRoundNumber()
            }
        }

        countDownTimer?.start()
    }

    private fun updateRoundNumber() {
        roundNumberTextView.text = currentRound.toString()
    }

    private fun vibrateOnFinish() {
        if (vibrator?.hasVibrator() == true) {
            // Vibrate for 500 milliseconds (adjust duration as needed)
            val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator?.vibrate(effect)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel() // Cancel the timer to prevent memory leaks
    }
}