package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the accessibility service switched off while no MCP tool needs it, and switches it on only for the
 * duration of a tool call (plus [IDLE_OFF_MS] of idle time).
 *
 * Opt-in, and only possible after the owner grants `WRITE_SECURE_SETTINGS` over ADB:
 * ```
 * adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
 * adb shell am broadcast ... ADB_CONFIGURE ... --ez a11y_on_demand true
 * ```
 * Apps that refuse to run while an accessibility service is enabled then only see it while a tool is running.
 */
object AccessibilityOnDemand {
    private const val TAG = "MCP:A11yOnDemand"
    private const val PREFS = "a11y_on_demand"
    private const val KEY_ENABLED = "enabled"
    private const val IDLE_OFF_MS = 90_000L
    private const val CONNECT_TIMEOUT_MS = 6_000L
    private const val POLL_MS = 100L
    private const val SEPARATOR = ':'

    /** Un-prefixed tool names (or prefixes ending in `_`) that never touch the accessibility service. */
    private val NO_ACCESSIBILITY =
        setOf(
            "notification_",
            "list_storage_locations",
            "list_files",
            "read_file",
            "write_file",
            "append_file",
            "file_replace",
            "delete_file",
            "download_from_url",
            "share_file_via_web",
            "get_shared_content",
            "list_apps",
            "open_app",
            "close_app",
            "send_intent",
            "open_uri",
            "get_location",
            "list_cameras",
            "list_camera_",
            "take_camera_",
            "save_camera_",
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null
    private var inFlight = 0
    private var idleJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isActive()) scheduleOff()
    }

    /** Whether on-demand mode is on: opted in over ADB and allowed to write secure settings. */
    fun isActive(): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) &&
            canWriteSecureSettings(ctx)
    }

    /** Turns on-demand mode on or off; off leaves the service enabled, as a normal install. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        appContext = context.applicationContext
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        when {
            !canWriteSecureSettings(context) -> Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; on-demand inactive")
            enabled -> scheduleOff()
            else -> switchService(context, on = true)
        }
        Log.i(TAG, "On-demand accessibility ${if (enabled) "enabled" else "disabled"}")
    }

    fun needsAccessibility(toolName: String): Boolean =
        NO_ACCESSIBILITY.none { if (it.endsWith("_")) toolName.startsWith(it) else toolName == it }

    /** Runs [block] with the accessibility service switched on when on-demand mode is active. */
    suspend fun <T> around(
        toolName: String,
        block: suspend () -> T,
    ): T {
        val ctx = appContext
        if (ctx == null || !isActive() || !needsAccessibility(toolName)) return block()
        synchronized(lock) {
            inFlight++
            idleJob?.cancel()
            idleJob = null
        }
        try {
            if (McpAccessibilityService.instance?.isReady() != true) {
                switchService(ctx, on = true)
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    while (McpAccessibilityService.instance?.isReady() != true) delay(POLL_MS)
                } ?: Log.w(TAG, "Accessibility service did not connect in time")
            }
            return block()
        } finally {
            synchronized(lock) {
                inFlight--
                if (inFlight == 0) scheduleOff()
            }
        }
    }

    private fun scheduleOff() {
        synchronized(lock) {
            idleJob?.cancel()
            idleJob =
                scope.launch {
                    delay(IDLE_OFF_MS)
                    val ctx = appContext ?: return@launch
                    val idle = synchronized(lock) { inFlight == 0 }
                    if (idle && isActive()) switchService(ctx, on = false)
                }
        }
    }

    private fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun switchService(
        context: Context,
        on: Boolean,
    ) {
        val resolver = context.contentResolver
        val component = ComponentName(context, McpAccessibilityService::class.java).flattenToString()
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val updated = if (on) withComponent(current, component) else withoutComponent(current, component)
        if (updated == (current ?: "")) return
        runCatching {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            if (on) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } else if (updated.isEmpty()) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
        }.onSuccess { Log.i(TAG, "Accessibility service switched ${if (on) "on" else "off"}") }
            .onFailure { Log.w(TAG, "Could not switch accessibility service", it) }
    }

    internal fun withComponent(
        list: String?,
        component: String,
    ): String {
        val items = split(list)
        return if (items.any { it.equals(component, ignoreCase = true) }) {
            items.joinToString(SEPARATOR.toString())
        } else {
            (items + component).joinToString(SEPARATOR.toString())
        }
    }

    internal fun withoutComponent(
        list: String?,
        component: String,
    ): String = split(list).filterNot { it.equals(component, ignoreCase = true) }.joinToString(SEPARATOR.toString())

    private fun split(list: String?): List<String> = list.orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
}
