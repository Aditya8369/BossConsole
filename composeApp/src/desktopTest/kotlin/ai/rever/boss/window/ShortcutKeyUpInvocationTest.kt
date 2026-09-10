package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BossConsole#490: a shortcut's bound action must fire on the primary key's KEY_RELEASED,
 * never on KEY_PRESSED (including OS auto-repeat presses while the key is held), and any
 * chord armed but not yet fired must be dropped on cancellation.
 *
 * These drive [AWTKeyboardInterceptor.handleKeyPressed] / [AWTKeyboardInterceptor.handleKeyReleased]
 * directly with synthetic AWT events - the same way [HostBindingPrecedenceTest] drives
 * [AWTKeyboardInterceptor.dispatchAction] directly - rather than going through [install]'s real
 * `KeyEventDispatcher` (which needs a registered AWT `Window`) or real chord matching (which
 * reads the on-disk keymap via `KeymapSettingsManager`, and so differs between a dev machine
 * and CI - the same reason [TabStepGateTest] and friends avoid it).
 * [AWTKeyboardInterceptor.pendingShortcut] is set directly to arm a known chord instead.
 */
class ShortcutKeyUpInvocationTest {
    private val source = Canvas()

    private fun keyEvent(
        id: Int,
        keyCode: Int,
        modifiers: Int = 0,
    ): KeyEvent = KeyEvent(source, id, System.currentTimeMillis(), modifiers, keyCode, keyCode.toChar())

    private fun tabNewBinding() =
        AWTKeyboardInterceptor.BindingMatch(
            KeyBinding(actionId = KeymapActions.TAB_NEW, key = "N", modifiers = listOf("Cmd")),
            KeyStroke("N", listOf("Cmd")),
        )

    private fun pendingFor(
        windowId: String,
        keyCode: Int = KeyEvent.VK_N,
    ) = AWTKeyboardInterceptor.PendingShortcut(keyCode = keyCode, windowId = windowId, hostBinding = tabNewBinding())

    @AfterTest
    fun clearPending() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
    }

    @Test
    fun `a matching key-up fires the action exactly once`() {
        AWTKeyboardInterceptor.pendingShortcut = pendingFor("keyup-once")

        val release = keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(release), "the first release must fire")
        assertNull(AWTKeyboardInterceptor.pendingShortcut, "firing must clear the armed chord")
        assertFalse(
            AWTKeyboardInterceptor.handleKeyReleased(release),
            "a second release of the same key must not fire again",
        )
    }

    @Test
    fun `a release of a different key does not fire the armed chord`() {
        val pending = pendingFor("keyup-mismatch")
        AWTKeyboardInterceptor.pendingShortcut = pending

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_W)))
        assertEquals(
            pending,
            AWTKeyboardInterceptor.pendingShortcut,
            "an unrelated release must leave the armed chord alone",
        )
    }

    @Test
    fun `releasing a modifier by itself does not fire the armed chord`() {
        // A modifier can never itself be the armed keyCode - handleKeyPressed rejects a
        // modifier-only press before arming (see the test below) - so its own release is just
        // another mismatch. Pinned explicitly since #490 names this acceptance criterion.
        val pending = pendingFor("keyup-modifier")
        AWTKeyboardInterceptor.pendingShortcut = pending

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL)))
        assertEquals(pending, AWTKeyboardInterceptor.pendingShortcut)
    }

    @Test
    fun `with no chord armed, a key-up does nothing`() {
        AWTKeyboardInterceptor.pendingShortcut = null
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }

    @Test
    fun `a repeat key-down for the armed key is claimed without re-arming or firing`() {
        val windowId = "keyup-repeat"
        val pending = pendingFor(windowId)
        AWTKeyboardInterceptor.pendingShortcut = pending

        // OS auto-repeat delivers KEY_PRESSED again and again while a key is held; each one
        // must stay claimed (so it doesn't leak to the focused component) without firing.
        val repeatPress = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, InputEvent.META_DOWN_MASK)
        repeat(5) {
            val claimed = AWTKeyboardInterceptor.handleKeyPressed(repeatPress, windowId)
            assertTrue(claimed, "a repeat press must stay claimed")
        }
        assertEquals(
            pending,
            AWTKeyboardInterceptor.pendingShortcut,
            "repeats must not re-arm or replace the pending chord",
        )

        // Still fires exactly once, on the eventual release - not once per repeat.
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
        assertNull(AWTKeyboardInterceptor.pendingShortcut)
    }

    @Test
    fun `a key-down with no modifier held is never armed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, 0)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-nomod"))
        assertNull(AWTKeyboardInterceptor.pendingShortcut)
    }

    @Test
    fun `a bare modifier key-down is never armed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-modonly"))
        assertNull(AWTKeyboardInterceptor.pendingShortcut)
    }

    @Test
    fun `the bound action's side effect - not just the claim - is decided at release, not arm`() {
        // TAB_NEXT_POSITIONAL is gated on MenuActionsHandler.canStepTabs. Arming while the gate
        // is open and then closing it before release proves the actual dispatch happens at
        // release: if key-down had already invoked the action (the pre-#490 behaviour), closing
        // the gate afterward could not change what already fired.
        val windowId = "keyup-gate-race"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)
        assertTrue(MenuActionsHandler.canStepTabs(windowId))

        val binding =
            KeyBinding(
                actionId = KeymapActions.TAB_NEXT_POSITIONAL,
                key = "CloseBracket",
                modifiers = listOf("Cmd", "Shift"),
            )
        val keystroke = KeyStroke("CloseBracket", listOf("Cmd", "Shift"))
        AWTKeyboardInterceptor.pendingShortcut =
            AWTKeyboardInterceptor.PendingShortcut(
                keyCode = KeyEvent.VK_CLOSE_BRACKET,
                windowId = windowId,
                hostBinding = AWTKeyboardInterceptor.BindingMatch(binding, keystroke),
            )

        // Close the gate before the key is ever released.
        MenuActionsHandler.updateActivePanelTabCount(windowId, 1)
        assertFalse(MenuActionsHandler.canStepTabs(windowId))

        assertFalse(
            AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_CLOSE_BRACKET)),
            "the gate closed before release, so nothing should fire",
        )
    }

    @Test
    fun `cancelling drops an armed chord so its eventual release does nothing`() {
        AWTKeyboardInterceptor.pendingShortcut = pendingFor("keyup-cancel")

        AWTKeyboardInterceptor.cancelPendingShortcut()

        assertNull(AWTKeyboardInterceptor.pendingShortcut)
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }

    @Test
    fun `a plugin-armed chord dispatches through PluginShortcutRegistryImpl once, at release`() {
        // No side-effect-free probe exists for a plugin dispatch (see PendingShortcut's KDoc),
        // so this only proves WHEN dispatch is attempted (once, at release, never at arm) via
        // an unregistered action id, which PluginShortcutRegistryImpl.dispatch reports as
        // unhandled either way - a crash or a second attempt would still fail this test.
        AWTKeyboardInterceptor.pendingShortcut =
            AWTKeyboardInterceptor.PendingShortcut(
                keyCode = KeyEvent.VK_N,
                windowId = "keyup-plugin",
                pluginActionId = "plugin.nonexistent.action",
            )

        val release = keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(release))
        assertNull(
            AWTKeyboardInterceptor.pendingShortcut,
            "the attempt still clears the armed state, even when unhandled",
        )
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(release), "nothing left to fire on a second release")
    }
}
