package com.DynnKyru.driveAware

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var tagline: TextView
    private lateinit var auth: FirebaseAuth
    private val handler = Handler(Looper.getMainLooper())

    private var progress = 0
    private val tips = listOf(
        "Analyzing objects...",
        "Preparing the Application...",
        "Calibrating sensors...",
        "Verifying road safety...",
        "Ginugulpi si michael...",
        "Ginugulpi si michael olet..."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the status bar
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // Hide the navigation bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // Set status bar and navigation bar colors to transparent
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // Set content view
        setContentView(R.layout.splash_screen)
        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // ANIMATIOOOOOOOOOOOOON PAGAWAAAAAAAAAAAAAAAAA
/*
        val backgroundImage = findViewById<ImageView>(R.id.splashBackground)

        val animation = ObjectAnimator.ofFloat(
            backgroundImage,
            "translationY",
            0f,
            200f  // Change this to how much you want it to scroll down
        ).apply {
            duration = 7000  // Duration in milliseconds (5 seconds)
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
        }

        animation.start()
*/



        // View bindings
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)
        tagline = findViewById(R.id.tagline)
        val appLogo = findViewById<ImageView>(R.id.appLogo)

        progressBar.max = 100

        // Animate logo
        appLogo.scaleX = 0.8f
        appLogo.scaleY = 0.8f
        appLogo.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(1f)
            .setDuration(1000)
            .start()

        // Animate tagline and loading text
        tagline.animate().alpha(1f).setStartDelay(500).setDuration(800).start()
        loadingText.animate().alpha(1f).setStartDelay(1000).setDuration(800).start()

        // Start animations and progress
        showTips()
        startSmoothProgress()
    }

    private fun startSmoothProgress() {
        handler.post(object : Runnable {
            override fun run() {
                if (progress < 100) {
                    progress++
                    progressBar.progress = progress
                    handler.postDelayed(this, 60) // Smooth 6-second animation
                } else {
                    // Now decide where to go AFTER splash animation
                    val nextActivity = if (auth.currentUser != null) {
                        Intent(this@SplashActivity, MainActivity::class.java)
                    } else {
                        Intent(this@SplashActivity, LoginNSignup::class.java).apply {
                            putExtra("showSignup", false)
                        }
                    }

                    startActivity(nextActivity)
                    finish()
                }
            }
        })
    }


    private fun showTips() {
        for (i in tips.indices) {
            handler.postDelayed({
                loadingText.text = tips[i]
            }, (i * 1500).toLong())
        }
    }
}
