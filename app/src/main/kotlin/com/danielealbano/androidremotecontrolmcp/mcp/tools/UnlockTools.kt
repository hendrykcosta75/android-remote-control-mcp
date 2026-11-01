package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.accessibilityservice.AccessibilityService
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.unlock.ScreenUnlocker
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

private fun requireService(provider: AccessibilityServiceProvider): AccessibilityService {
    if (!provider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
        )
    }
    return provider.getContext() as? AccessibilityService
        ?: throw McpToolException.ActionFailed("Accessibility service context unavailable")
}

// ─────────────────────────────────────────────────────────────────────────────
// unlock_screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `unlock_screen`.
 *
 * Wakes the screen and enters the PIN stored on the device (configured only via adb, extra `unlock_pin`).
 * One attempt: on failure the tool locks itself out until adb clears it (extra `unlock_reset_lockout`).
 * The PIN is never accepted as a parameter nor returned.
 */
class UnlockScreenHandler(
    private val accessibilityServiceProvider: AccessibilityServiceProvider,
) {
    @Suppress("UnusedParameter")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val outcome = ScreenUnlocker.unlock(requireService(accessibilityServiceProvider))
        return McpToolUtils.textResult(outcome.toJson())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Wakes the screen and unlocks the device with the PIN stored on the device (set via adb). " +
                    "Takes no parameters. Returns JSON with status: unlocked, already_unlocked, no_pin_configured, " +
                    "locked_out, wake_failed, keypad_not_found or unlock_failed_locked_out. Makes ONE attempt; " +
                    "after a failure it refuses until the owner clears the lockout via adb, so never retry blindly.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = listOf()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "unlock_screen"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// lock_screen
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `lock_screen`: locks the device via the accessibility global action. */
class LockScreenHandler(
    private val accessibilityServiceProvider: AccessibilityServiceProvider,
) {
    @Suppress("UnusedParameter")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!ScreenUnlocker.lock(requireService(accessibilityServiceProvider))) {
            throw McpToolException.ActionFailed("Lock screen action failed")
        }
        return McpToolUtils.textResult("Lock screen executed successfully")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description = "Locks the device and turns the screen off. Takes no parameters.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = listOf()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "lock_screen"
    }
}
