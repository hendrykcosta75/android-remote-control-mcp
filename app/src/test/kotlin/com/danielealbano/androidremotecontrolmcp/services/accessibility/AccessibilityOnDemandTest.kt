package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessibilityOnDemandTest {
    private val me = "com.example/com.example.Svc"
    private val other = "com.other/com.other.Svc"

    @Test
    fun `withComponent appends to an empty or null list`() {
        assertEquals(me, AccessibilityOnDemand.withComponent(null, me))
        assertEquals(me, AccessibilityOnDemand.withComponent("", me))
    }

    @Test
    fun `withComponent keeps other services and does not duplicate`() {
        assertEquals("$other:$me", AccessibilityOnDemand.withComponent(other, me))
        assertEquals("$other:$me", AccessibilityOnDemand.withComponent("$other:$me", me))
    }

    @Test
    fun `withoutComponent removes only this service`() {
        assertEquals(other, AccessibilityOnDemand.withoutComponent("$other:$me", me))
        assertEquals("", AccessibilityOnDemand.withoutComponent(me, me))
        assertEquals("", AccessibilityOnDemand.withoutComponent(null, me))
    }

    @Test
    fun `needsAccessibility excludes tools that never use the service`() {
        assertTrue(AccessibilityOnDemand.needsAccessibility("get_screen_state"))
        assertTrue(AccessibilityOnDemand.needsAccessibility("unlock_screen"))
        assertFalse(AccessibilityOnDemand.needsAccessibility("notification_list"))
        assertFalse(AccessibilityOnDemand.needsAccessibility("read_file"))
        assertFalse(AccessibilityOnDemand.needsAccessibility("take_camera_photo"))
    }
}
