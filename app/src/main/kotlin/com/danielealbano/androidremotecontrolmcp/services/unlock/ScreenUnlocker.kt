package com.danielealbano.androidremotecontrolmcp.services.unlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wakes the screen and enters the stored PIN on the system keyguard via accessibility.
 *
 * Safety rules:
 * - one attempt only: a failed attempt sets a lockout that only adb can clear ([UnlockPinStore]);
 * - keys are pressed only when a full numeric keypad (all ten digits) is found in a System UI window,
 *   never by blind coordinates;
 * - the PIN is never logged nor returned.
 */
object ScreenUnlocker {
    private const val TAG = "MCP:ScreenUnlocker"

    data class Outcome(
        val status: String,
        val wakeMethod: String? = null,
        val digitKeysFound: Int = 0,
        val confirmPressed: Boolean = false,
        val swipes: Int = 0,
        val systemUiWindows: Int = 0,
    ) {
        fun toJson(): String =
            """{"status":"$status","wake_method":${wakeMethod?.let { "\"$it\"" } ?: "null"},""" +
                """"digit_keys_found":$digitKeysFound,"confirm_pressed":$confirmPressed,""" +
                """"swipes":$swipes,"systemui_windows":$systemUiWindows}"""
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    suspend fun unlock(service: AccessibilityService): Outcome {
        val store = UnlockPinStore(service)
        if (store.isLockedOut()) return Outcome("locked_out")
        if (!store.hasPin()) return Outcome("no_pin_configured")
        val km = service.getSystemService(KeyguardManager::class.java)
        val pm = service.getSystemService(PowerManager::class.java)
        if (!km.isDeviceLocked) return Outcome("already_unlocked")

        // 1) Wake the screen.
        var wake = "already_on"

        @Suppress("DEPRECATION")
        val wl =
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "arc:unlock",
            )
        try {
            if (!pm.isInteractive) {
                wl.acquire(WAKE_LOCK_MS)
                wake = if (waitFor(WAKE_WAIT_MS) { pm.isInteractive }) "wake_lock" else "none"
            }
            // 2) Fallback wake path only: a self-finishing activity with setTurnScreenOn. It must not stay on
            //    top, because on One UI a show-when-locked activity occludes the keyguard and hides the keypad.
            if (wake == "none") {
                startWakeActivity(service)
                wake = if (waitFor(WAKE_WAIT_MS) { pm.isInteractive }) "activity" else "none"
                if (wake == "none") return Outcome("wake_failed", wake)
                delay(ACTIVITY_SETTLE_MS)
            }

            // 3) Let the keyguard settle, then swipe up (at most twice) until a full numeric keypad shows up.
            delay(KEYGUARD_SETTLE_MS)
            var keys = KeyguardKeypad.findDigitKeys(service)
            var swipes = 0
            while (keys.size < DIGITS && swipes < MAX_SWIPES) {
                swipeUp(service)
                swipes++
                keys = waitForKeys(service, KEYPAD_WAIT_MS)
            }
            if (keys.size < DIGITS) {
                return Outcome(
                    "keypad_not_found",
                    wake,
                    keys.size,
                    swipes = swipes,
                    systemUiWindows = KeyguardKeypad.systemUiWindowCount(service),
                )
            }

            // 4) Enter the PIN (pressing only keys that were found).
            val pin = store.readPin() ?: return Outcome("pin_unreadable", wake, keys.size)
            try {
                if (pin.any { it !in keys }) return Outcome("pin_has_non_digit_chars", wake, keys.size)
                for (c in pin) {
                    press(service, keys.getValue(c))
                    delay(KEY_GAP_MS)
                }
            } finally {
                pin.fill('\u0000')
            }

            // 5) Confirm if the PIN pad does not auto-submit.
            var confirmed = false
            if (!waitFor(AUTO_SUBMIT_WAIT_MS) { !km.isDeviceLocked }) {
                KeyguardKeypad.findConfirmKey(service)?.let {
                    press(service, it)
                    confirmed = true
                }
            }
            val ok = waitFor(UNLOCK_WAIT_MS) { !km.isDeviceLocked }
            if (!ok) {
                store.setLockedOut()
                Log.w(TAG, "Unlock attempt failed; lockout set (clear via adb)")
                return Outcome("unlock_failed_locked_out", wake, keys.size, confirmed, swipes)
            }
            return Outcome("unlocked", wake, keys.size, confirmed, swipes)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    fun lock(service: AccessibilityService): Boolean {
        val action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        return service.performGlobalAction(action)
    }

    private fun startWakeActivity(service: AccessibilityService) {
        try {
            service.startActivity(
                Intent(service, UnlockWakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Wake activity start failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun waitForKeys(
        service: AccessibilityService,
        timeoutMs: Long,
    ): Map<Char, AccessibilityNodeInfo> {
        var keys: Map<Char, AccessibilityNodeInfo> = emptyMap()
        waitFor(timeoutMs) {
            keys = KeyguardKeypad.findDigitKeys(service)
            keys.size >= DIGITS
        }
        return keys
    }

    private suspend fun press(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        tap(service, r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun tap(
        service: AccessibilityService,
        x: Float,
        y: Float,
    ) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(service, stroke(path, TAP_MS))
    }

    private suspend fun swipeUp(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path =
            Path().apply {
                moveTo(x, dm.heightPixels * SWIPE_FROM)
                lineTo(x, dm.heightPixels * SWIPE_TO)
            }
        dispatch(service, stroke(path, SWIPE_MS))
    }

    private fun stroke(
        path: Path,
        durationMs: Long,
    ): GestureDescription {
        val s = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(s).build()
    }

    private suspend fun dispatch(
        service: AccessibilityService,
        g: GestureDescription,
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val cb =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            if (!service.dispatchGesture(g, cb, null) && cont.isActive) cont.resume(false)
        }

    private suspend fun waitFor(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(POLL_MS)
        }
        return cond()
    }

    private const val DIGITS = 10
    private const val WAKE_LOCK_MS = 15_000L
    private const val WAKE_WAIT_MS = 2_500L
    private const val KEYPAD_WAIT_MS = 3_000L
    private const val KEYGUARD_SETTLE_MS = 700L
    private const val ACTIVITY_SETTLE_MS = 1_200L
    private const val MAX_SWIPES = 2
    private const val KEY_GAP_MS = 120L
    private const val AUTO_SUBMIT_WAIT_MS = 1_200L
    private const val UNLOCK_WAIT_MS = 3_000L
    private const val POLL_MS = 100L
    private const val TAP_MS = 60L
    private const val SWIPE_MS = 300L
    private const val SWIPE_FROM = 0.85f
    private const val SWIPE_TO = 0.35f
}
