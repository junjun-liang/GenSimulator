package com.flyme.auto.gensimulator.bridge

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/**
 * Visible, debug-only bootstrap for the Bridge foreground service.
 *
 * ADB can launch this explicit activity when the Bridge process is stopped. Starting the service
 * from a resumed activity avoids the platform's background-service cold-start restriction.
 */
class BridgeBootstrapActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finishAndRemoveTask() }
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "GenSimulator Bridge is starting"
            gravity = Gravity.CENTER
            textSize = 18f
        })
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        val serviceIntent = Intent(this, BridgeRuntimeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        mainHandler.postDelayed(finishRunnable, FINISH_DELAY_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    private companion object {
        const val FINISH_DELAY_MS = 1_000L
    }
}
