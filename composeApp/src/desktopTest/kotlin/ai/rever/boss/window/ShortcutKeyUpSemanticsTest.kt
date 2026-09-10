package ai.rever.boss.window

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AWTKeyboardInterceptor] key release semantics.
 *
 * Verifies that:
 * 1. A matching KEY_PRESSED event arms the chord and is consumed without dispatching the action.
 * 2. Auto-repeat KEY_PRESSED events are consumed and do not dispatch the action.
 * 3. The matching KEY_RELEASED event of the primary key dispatches the action and is consumed.
 * 4. Releasing a modifier alone cancels the pending shortcut and does not dispatch.
 * 5. Window unregister or clearPendingShortcut cancels the pending shortcut.
 */
class ShortcutKeyUpSemanticsTest {
    private lateinit var frame: JFrame
    private lateinit var canvas: Canvas
    private val windowId = "test-window-key-release"
    private lateinit var testScope: CoroutineScope
    private val newTabEventCount = AtomicInteger(0)
    private var collectorJob: Job? = null

    @Suppress("DEPRECATION")
    private val primaryModifierMask =
        if (SystemUtils.isMacOS) {
            InputEvent.META_DOWN_MASK or InputEvent.META_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK or InputEvent.CTRL_MASK
        }
    private val modifierKeyCode =
        if (SystemUtils.isMacOS) KeyEvent.VK_META else KeyEvent.VK_CONTROL

    @BeforeTest
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Unconfined)
        newTabEventCount.set(0)
        collectorJob =
            testScope.launch {
                MenuActionsHandler.newTabEvents.collect { winId ->
                    if (winId == windowId) {
                        newTabEventCount.incrementAndGet()
                    }
                }
            }

        AWTKeyboardInterceptor.install()
        frame = JFrame("Test Frame")
        frame.iconImages = BossWindowIcon.images
        canvas = Canvas()
        frame.add(canvas)
        frame.pack()
        AWTKeyboardInterceptor.registerWindow(frame, windowId)
        AWTKeyboardInterceptor.clearPendingShortcut()

        // Configure a known test binding
        val binding =
            KeyBinding(
                actionId = KeymapActions.TAB_NEW,
                key = "T",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )
        runBlocking {
            KeymapSettingsManager.updateSettings(KeymapSettings.fromBindings(listOf(binding)))
        }
    }

    @AfterTest
    fun tearDown() {
        AWTKeyboardInterceptor.clearPendingShortcut()
        AWTKeyboardInterceptor.unregisterWindow(frame)
        AWTKeyboardInterceptor.uninstall()
        frame.dispose()
        collectorJob?.cancel()
        testScope.cancel()
    }

    private fun dispatchKeyEvent(event: KeyEvent): Boolean = AWTKeyboardInterceptor.processKeyEvent(event)

    @Test
    fun `matching KEY_PRESSED arms pending shortcut and consumes without action dispatch`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )

        val consumed = dispatchKeyEvent(keyDown)

        assertTrue(consumed, "KeyDown matching shortcut must be consumed")
        assertTrue(keyDown.isConsumed, "KeyEvent must be marked consumed")
        assertTrue(
            AWTKeyboardInterceptor.hasPendingShortcut(),
            "AWTKeyboardInterceptor must have pending shortcut armed",
        )
        assertEquals(0, newTabEventCount.get(), "Action must not be dispatched on key-down")
    }

    @Test
    fun `matching KEY_RELEASED on primary key dispatches action exactly once`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertEquals(0, newTabEventCount.get(), "Action must not dispatch on KeyDown")

        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertTrue(consumed, "KeyUp on primary key must be consumed")
        assertTrue(keyUp.isConsumed, "KeyUp event must be marked consumed")
        assertEquals(1, newTabEventCount.get(), "Action must be dispatched exactly once on KeyUp")
        assertFalse(AWTKeyboardInterceptor.hasPendingShortcut(), "Pending shortcut must be cleared after dispatch")
    }

    @Test
    fun `auto-repeat KEY_PRESSED events do not cause multiple dispatches`() {
        // Simulate 5 auto-repeat key-down events
        repeat(5) {
            val keyDown =
                KeyEvent(
                    canvas,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    primaryModifierMask,
                    KeyEvent.VK_T,
                    'T',
                )
            val consumed = dispatchKeyEvent(keyDown)
            assertTrue(consumed, "Auto-repeat KeyDown must be consumed")
            assertEquals(0, newTabEventCount.get(), "Auto-repeat must not dispatch action")
        }

        // Release primary key
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertTrue(consumed, "KeyUp must be consumed")
        assertEquals(1, newTabEventCount.get(), "Action must be dispatched exactly once on release")
    }

    @Test
    fun `releasing modifier alone does not dispatch action and cancels pending shortcut`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertTrue(AWTKeyboardInterceptor.hasPendingShortcut())

        // Release modifier key alone (e.g. Cmd/Ctrl)
        val modifierKeyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                modifierKeyCode,
                KeyEvent.CHAR_UNDEFINED,
            )
        val consumed = dispatchKeyEvent(modifierKeyUp)

        assertFalse(consumed, "Releasing modifier alone should not be consumed as shortcut execution")
        assertEquals(0, newTabEventCount.get(), "Action must not be dispatched when modifier is released alone")
        assertFalse(
            AWTKeyboardInterceptor.hasPendingShortcut(),
            "Pending shortcut must be cancelled on modifier release",
        )

        // Subsequent primary key release does nothing
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_T,
                'T',
            )
        val keyUpConsumed = dispatchKeyEvent(keyUp)

        assertFalse(keyUpConsumed)
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `clearPendingShortcut cancels pending shortcut on focus loss or window unregister`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertTrue(AWTKeyboardInterceptor.hasPendingShortcut())

        // Reset/cancel pending shortcut
        AWTKeyboardInterceptor.clearPendingShortcut()
        assertFalse(AWTKeyboardInterceptor.hasPendingShortcut())

        // Key up after cancellation should not dispatch action
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertFalse(consumed)
        assertEquals(0, newTabEventCount.get())
    }
}
