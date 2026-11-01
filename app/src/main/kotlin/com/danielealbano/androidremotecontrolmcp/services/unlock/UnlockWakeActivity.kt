package com.danielealbano.androidremotecontrolmcp.services.unlock

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Fallback wake path for [ScreenUnlocker]: turns the screen on and finishes right away.
 *
 * It must not linger: on One UI a show-when-locked activity occludes the keyguard, so the PIN keypad
 * would never appear while it is on top. It does not request keyguard dismissal for the same reason.
 */
class UnlockWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MS)
    }

    companion object {
        private const val FINISH_DELAY_MS = 400L
    }
}
