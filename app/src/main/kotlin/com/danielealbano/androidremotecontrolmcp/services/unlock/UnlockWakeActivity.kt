package com.danielealbano.androidremotecontrolmcp.services.unlock

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Transparent activity that asks the system to turn the screen on and to show the keyguard bouncer.
 * Started by [ScreenUnlocker] from the accessibility service (which is exempt from background
 * activity-start limits). Finishes itself shortly after.
 */
class UnlockWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MS)
    }

    companion object {
        private const val FINISH_DELAY_MS = 15_000L
    }
}
