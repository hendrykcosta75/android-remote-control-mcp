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
    private const val SYSTEMUI = "com.android.systemui"
    private val KEY_ID = Regex("""(?:^|[:/_])key(\d)$""")
    private val CONFIRM_ID = Regex("""(key_enter|ok_button|okbutton|key_ok|enter)$""", RegexOption.IGNORE_CASE)
    private val CONFIRM_LABELS = setOf("ok", "enter", "confirmar", "concluído", "concluido", "done", "entrar")

    data class Outcome(
        val status: String,
        val wakeMethod: String? = null,
        val digitKeysFound: Int = 0,
        val confirmPressed: Boolean = false,
    ) {
        fun toJson(): String =
            """{"status":"$status","wake_method":${wakeMethod?.let { "\"$it\"" } ?: "null"},""" +
                """"digit_keys_found":$digitKeysFound,"confirm_pressed":$confirmPressed}"""
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
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "arc:unlock",
        )
        try {
            if (!pm.isInteractive) {
                wl.acquire(WAKE_LOCK_MS)
                wake = if (waitFor(WAKE_WAIT_MS) { pm.isInteractive }) "wake_lock" else "none"
            }
            // 2) Show the bouncer (also a second wake path through setTurnScreenOn).
            startWakeActivity(service)
            if (wake == "none") {
                wake = if (waitFor(WAKE_WAIT_MS) { pm.isInteractive }) "activity" else "none"
                if (wake == "none") return Outcome("wake_failed", wake)
            }

            // 3) Find a full numeric keypad; if absent, try a swipe up once.
            var keys = waitForKeys(service, KEYPAD_WAIT_MS)
            if (keys.size < DIGITS) {
                swipeUp(service)
                keys = waitForKeys(service, KEYPAD_WAIT_MS)
            }
            if (keys.size < DIGITS) return Outcome("keypad_not_found", wake, keys.size)

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
                findConfirmKey(service)?.let { press(service, it); confirmed = true }
            }
            val ok = waitFor(UNLOCK_WAIT_MS) { !km.isDeviceLocked }
            if (!ok) {
                store.setLockedOut()
                Log.w(TAG, "Unlock attempt failed; lockout set (clear via adb)")
                return Outcome("unlock_failed_locked_out", wake, keys.size, confirmed)
            }
            return Outcome("unlocked", wake, keys.size, confirmed)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    fun lock(service: AccessibilityService): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    private fun startWakeActivity(service: AccessibilityService) {
        try {
            service.startActivity(
                Intent(service, UnlockWakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Wake activity start failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun waitForKeys(service: AccessibilityService, timeoutMs: Long): Map<Char, AccessibilityNodeInfo> {
        var keys: Map<Char, AccessibilityNodeInfo> = emptyMap()
        waitFor(timeoutMs) {
            keys = findDigitKeys(service)
            keys.size >= DIGITS
        }
        return keys
    }

    private fun systemUiRoots(service: AccessibilityService): List<AccessibilityNodeInfo> =
        service.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == SYSTEMUI }

    private fun findDigitKeys(service: AccessibilityService): Map<Char, AccessibilityNodeInfo> {
        val found = HashMap<Char, AccessibilityNodeInfo>()
        for (root in systemUiRoots(service)) {
            walk(root) { n ->
                val digit = digitOf(n) ?: return@walk
                val target = clickableSelfOrAncestor(n) ?: return@walk
                found.putIfAbsent(digit, target)
            }
        }
        return found
    }

    private fun findConfirmKey(service: AccessibilityService): AccessibilityNodeInfo? {
        for (root in systemUiRoots(service)) {
            var hit: AccessibilityNodeInfo? = null
            walk(root) { n ->
                if (hit != null) return@walk
                val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
                val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
                if (CONFIRM_ID.containsMatchIn(id) || label in CONFIRM_LABELS) hit = clickableSelfOrAncestor(n)
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun digitOf(n: AccessibilityNodeInfo): Char? {
        n.viewIdResourceName?.let { id -> KEY_ID.find(id)?.let { return it.groupValues[1][0] } }
        for (label in listOf(n.text, n.contentDescription)) {
            val v = label?.toString()?.trim() ?: continue
            if (v.length == 1 && v[0].isDigit()) return v[0]
        }
        return null
    }

    private fun clickableSelfOrAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(ANCESTOR_DEPTH) {
            val c = cur ?: return null
            if (c.isClickable && c.isEnabled) return c
            cur = c.parent
        }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > MAX_DEPTH) return
        visit(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, visit) }
    }

    private suspend fun press(service: AccessibilityService, node: AccessibilityNodeInfo) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        tap(service, r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun tap(service: AccessibilityService, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(service, GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build())
    }

    private suspend fun swipeUp(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * SWIPE_FROM)
            lineTo(x, dm.heightPixels * SWIPE_TO)
        }
        dispatch(service, GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS)).build())
    }

    private suspend fun dispatch(service: AccessibilityService, g: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }
            if (!service.dispatchGesture(g, cb, null) && cont.isActive) cont.resume(false)
        }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
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
    private const val KEY_GAP_MS = 120L
    private const val AUTO_SUBMIT_WAIT_MS = 1_200L
    private const val UNLOCK_WAIT_MS = 3_000L
    private const val POLL_MS = 100L
    private const val TAP_MS = 60L
    private const val SWIPE_MS = 300L
    private const val SWIPE_FROM = 0.8f
    private const val SWIPE_TO = 0.3f
    private const val ANCESTOR_DEPTH = 4
    private const val MAX_DEPTH = 40
}
