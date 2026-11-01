package com.danielealbano.androidremotecontrolmcp.services.unlock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/** Finds the keyguard PIN keypad keys in System UI windows through the accessibility tree. */
internal object KeyguardKeypad {
    private const val SYSTEMUI = "com.android.systemui"
    private const val ANCESTOR_DEPTH = 4
    private const val MAX_DEPTH = 40
    private val KEY_ID = Regex("""(?:^|[:/_])key(\d)$""")
    private val CONFIRM_ID = Regex("""(key_enter|ok_button|okbutton|key_ok|enter)$""", RegexOption.IGNORE_CASE)
    private val CONFIRM_LABELS = setOf("ok", "enter", "confirmar", "concluído", "concluido", "done", "entrar")

    fun systemUiWindowCount(service: AccessibilityService): Int = systemUiRoots(service).size

    /** Maps each digit to the clickable node that types it; only System UI windows are searched. */
    fun findDigitKeys(service: AccessibilityService): Map<Char, AccessibilityNodeInfo> {
        val found = HashMap<Char, AccessibilityNodeInfo>()
        for (root in systemUiRoots(service)) {
            walk(root) { n ->
                val digit = digitOf(n)
                val target = digit?.let { clickableSelfOrAncestor(n) }
                if (digit != null && target != null) found.putIfAbsent(digit, target)
            }
        }
        return found
    }

    fun findConfirmKey(service: AccessibilityService): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        for (root in systemUiRoots(service)) {
            walk(root) { n ->
                if (hit == null && isConfirm(n)) hit = clickableSelfOrAncestor(n)
            }
            if (hit != null) break
        }
        return hit
    }

    private fun systemUiRoots(service: AccessibilityService): List<AccessibilityNodeInfo> =
        service.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == SYSTEMUI }

    private fun isConfirm(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
        val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
        return CONFIRM_ID.containsMatchIn(id) || label in CONFIRM_LABELS
    }

    private fun digitOf(n: AccessibilityNodeInfo): Char? {
        val fromId =
            n.viewIdResourceName?.let {
                KEY_ID
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.first()
            }
        return fromId ?: listOf(n.text, n.contentDescription)
            .mapNotNull { it?.toString()?.trim() }
            .firstOrNull { it.length == 1 && it[0].isDigit() }
            ?.first()
    }

    private fun clickableSelfOrAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val chain = generateSequence(n) { it.parent }.take(ANCESTOR_DEPTH)
        return chain.firstOrNull { it.isClickable && it.isEnabled }
    }

    private fun walk(
        n: AccessibilityNodeInfo,
        depth: Int = 0,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        visit(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, visit) }
    }
}
