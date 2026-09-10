package ai.rever.boss.window

import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.keymap.model.canonicalModifiers
import ai.rever.boss.utils.SystemUtils
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * AWT-level keyboard interceptor that captures keyboard shortcuts before they reach
 * Swing/AWT components like BossTerm terminals.
 *
 * This solves the issue where BossTerm consumes all keyboard events for terminal emulation,
 * preventing global shortcuts (Cmd+N, Cmd+W, etc.) from working.
 *
 * The interceptor uses KeyboardFocusManager to intercept events at the AWT level,
 * checks if they match registered shortcuts, and dispatches actions through
 * MenuActionsHandler if matched.
 */
object AWTKeyboardInterceptor {
    private var isInstalled = false
    private var dispatcher: KeyEventDispatcher? = null

    /**
     * Map of AWT window to BOSS window ID for routing events to correct window.
     * Uses ConcurrentHashMap for thread-safety (AWT events come from EDT).
     */
    private val windowIdMap = ConcurrentHashMap<Window, String>()

    /**
     * Per-window active context tracking.
     * Updated by Compose layer when the active tab type changes.
     */
    private val windowContextMap = ConcurrentHashMap<String, ShortcutContext>()

    // Double-shift detection for global search (like IntelliJ's Search Everywhere)
    private var lastShiftPressTime: Long = 0
    private var lastShiftReleaseTime: Long = 0
    private var shiftPressCount: Int = 0

    // 500ms threshold follows accessibility guidelines for double-tap gestures (typically 500-800ms)
    private const val DOUBLE_SHIFT_THRESHOLD_MS = 500

    // MRU tab-cycle tracking. Set when Ctrl+Tab starts a cycle in MRU mode, alongside the
    // physical keycode of the modifier sustaining it; the cycle commits only when THAT
    // modifier is released. This avoids (a) emitting a commit on every unrelated Ctrl/Cmd
    // keyup, and (b) committing early when a different modifier is released mid-cycle.
    // Accessed only from the AWT event dispatch thread. Process-global (like the double-
    // shift state above): cycling in one window then focusing another without releasing the
    // modifier is a benign mismatch — the stray release just no-ops downstream.
    private var tabCycleActive = false
    private var tabCycleModifierKeyCode = -1

    // Minimum time shift must be released to count as a clean release (prevents false positives from held shift)
    private const val MIN_SHIFT_RELEASE_MS = 50

    /**
     * Pending shortcut chord armed on KEY_PRESSED awaiting primary key release on KEY_RELEASED.
     */
    internal data class PendingShortcut(
        val actionId: String,
        val windowId: String,
        val primaryKeyCode: Int,
        val isPluginShortcut: Boolean = false,
        val tabCycleMatch: BindingMatch? = null,
    )

    private var pendingShortcut: PendingShortcut? = null

    /**
     * Check if a shortcut chord is currently pending release.
     */
    internal fun hasPendingShortcut(): Boolean = pendingShortcut != null

    /**
     * Clear any pending shortcut state (e.g. on focus loss, window unregister, or cancellation).
     */
    fun clearPendingShortcut() {
        pendingShortcut = null
    }

    /**
     * Register an AWT window with its BOSS window ID.
     * Call this from BossWindow's DisposableEffect when window is created.
     */
    fun registerWindow(
        awtWindow: Window,
        windowId: String,
    ) {
        windowIdMap[awtWindow] = windowId
    }

    /**
     * Unregister an AWT window when it's closed.
     * Call this from BossWindow's DisposableEffect onDispose.
     */
    fun unregisterWindow(awtWindow: Window) {
        clearPendingShortcut()
        val windowId = windowIdMap.remove(awtWindow)
        if (windowId != null) {
            windowContextMap.remove(windowId)
        }
    }

    /**
     * Update the active shortcut context for a window.
     * Called from the Compose layer when the active tab type changes.
     *
     * NOT CALLED TODAY - no production caller sets a window context, so [windowContextMap] is
     * always empty and [detectCurrentContext] answers purely from the AWT focus walk. That walk
     * can only see a heavyweight component, i.e. JxBrowser's page surface, so BROWSER-context
     * bindings resolve while the PAGE has focus and not while focus is in a browser's Compose
     * chrome (address bar, tab strip, find bar). Wiring this up would fix that class, but it is
     * window-scoped: with a browser in the main panel and focus in a SIDEBAR editor it would
     * report BROWSER and hand Cmd+F and Cmd+R to the browser, which is why it stays unwired
     * here. See the Cmd+L note in `KeymapPresets.standardBrowserBindings`.
     *
     * @param windowId The BOSS window ID
     * @param context The shortcut context of the currently active component
     */
    fun updateWindowContext(
        windowId: String,
        context: ShortcutContext,
    ) {
        windowContextMap[windowId] = context
    }

    /**
     * Clear the active context for a window (reverts to GLOBAL).
     *
     * @param windowId The BOSS window ID
     */
    fun clearWindowContext(windowId: String) {
        windowContextMap.remove(windowId)
    }

    /**
     * Install the global keyboard interceptor.
     * Should be called once at application startup.
     */
    fun install() {
        if (isInstalled) return

        dispatcher = KeyEventDispatcher { event -> processKeyEvent(event) }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        isInstalled = true
    }

    /**
     * Process an AWT KeyEvent for shortcut handling.
     * Arms on KEY_PRESSED, executes on KEY_RELEASED of primary key.
     */
    internal fun processKeyEvent(event: KeyEvent): Boolean {
        // Handle double-shift detection for global search
        if (event.keyCode == KeyEvent.VK_SHIFT) {
            val currentTime = System.currentTimeMillis()

            when (event.id) {
                KeyEvent.KEY_PRESSED -> {
                    // Check if this is a quick second press after a clean release
                    val timeSinceRelease = currentTime - lastShiftReleaseTime
                    if (timeSinceRelease < DOUBLE_SHIFT_THRESHOLD_MS &&
                        timeSinceRelease >= MIN_SHIFT_RELEASE_MS && // Ensure clean release (not held)
                        shiftPressCount == 1
                    ) {
                        // Double-shift detected!
                        shiftPressCount = 0
                        lastShiftPressTime = 0
                        lastShiftReleaseTime = 0

                        // Get the focused window's BOSS window ID
                        val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                        val windowId = findWindowId(focusedWindow)
                        if (windowId != null) {
                            try {
                                MenuActionsHandler.triggerOpenGlobalSearch(windowId)
                                event.consume()
                                return true
                            } catch (e: Exception) {
                                // Log but don't crash the event dispatcher
                                System.err.println("Error triggering global search: ${e.message}")
                            }
                        }
                    } else {
                        // First shift press or timeout - start counting
                        shiftPressCount = 1
                        lastShiftPressTime = currentTime
                    }
                }

                KeyEvent.KEY_RELEASED -> {
                    // Record release time for detecting second press
                    if (shiftPressCount == 1 && currentTime - lastShiftPressTime < DOUBLE_SHIFT_THRESHOLD_MS) {
                        lastShiftReleaseTime = currentTime
                    } else {
                        // Too slow or wrong sequence - reset
                        shiftPressCount = 0
                    }
                }
            }
            return false // Let shift events propagate
        }

        // Reset double-shift state if any other key is pressed
        if (event.id == KeyEvent.KEY_PRESSED && !isModifierOnlyKey(event.keyCode)) {
            shiftPressCount = 0
            lastShiftPressTime = 0
            lastShiftReleaseTime = 0
        }

        // Commit an in-progress MRU tab cycle when its own cycling modifier is released.
        // Only fires while a cycle is active and only for that specific modifier, so
        // unrelated modifier keyups don't churn the UI thread or commit prematurely.
        // The release itself is not consumed.
        if (event.id == KeyEvent.KEY_RELEASED && tabCycleActive && event.keyCode == tabCycleModifierKeyCode) {
            tabCycleActive = false
            val focusedWindow = resolveWindow(event)
            findWindowId(focusedWindow)?.let { MenuActionsHandler.triggerCommitTabCycle(it) }
            return false
        }

        // Handle KEY_RELEASED for pending shortcuts
        if (event.id == KeyEvent.KEY_RELEASED) {
            val pending = pendingShortcut
            if (pending != null) {
                if (event.keyCode == pending.primaryKeyCode) {
                    // Primary key released: invoke the bound action!
                    pendingShortcut = null
                    if (pending.isPluginShortcut) {
                        val handled = PluginShortcutRegistryImpl.dispatch(pending.actionId, pending.windowId)
                        if (handled) {
                            event.consume()
                            return true
                        }
                    } else {
                        val handled = dispatchAction(pending.actionId, pending.windowId)
                        if (handled) {
                            val match = pending.tabCycleMatch
                            if (match != null &&
                                (match.binding.actionId == KeymapActions.TAB_NEXT || match.binding.actionId == KeymapActions.TAB_PREVIOUS) &&
                                KeymapSettingsManager.currentSettings.value.tabSwitchMode == TabSwitchMode.MRU
                            ) {
                                tabCycleActive = true
                                tabCycleModifierKeyCode = cyclingModifierKeyCode(match.keystroke)
                            }
                            event.consume()
                            return true
                        }
                    }
                    return false
                } else if (isModifierOnlyKey(event.keyCode)) {
                    // Releasing a modifier by itself cancels the pending shortcut chord
                    pendingShortcut = null
                    return false
                } else {
                    // An unrelated key was released
                    pendingShortcut = null
                    return false
                }
            }
            return false
        }

        // Only intercept KEY_PRESSED events for other shortcuts
        if (event.id != KeyEvent.KEY_PRESSED) {
            return false
        }

        // Skip if no modifier keys are pressed (most shortcuts require modifiers)
        if (!event.isMetaDown && !event.isControlDown && !event.isAltDown) {
            pendingShortcut = null
            return false
        }

        // Skip modifier-only key presses
        if (isModifierOnlyKey(event.keyCode)) {
            return false
        }

        // Auto-repeat check: if this key is already pending, consume without re-triggering
        val currentPending = pendingShortcut
        if (currentPending != null && currentPending.primaryKeyCode == event.keyCode) {
            event.consume()
            return true
        }

        // Get the focused window's BOSS window ID
        val focusedWindow = resolveWindow(event)
        val windowId = findWindowId(focusedWindow) ?: return false

        // Try to match the key event against shortcuts
        val match = findMatchingBinding(event)
        val binding = match?.binding

        if (binding != null) {
            if (!canDispatchAction(binding.actionId, windowId)) {
                pendingShortcut = null
                return false
            }

            // Arm the shortcut to trigger on key release and consume the key down event
            pendingShortcut =
                PendingShortcut(
                    actionId = binding.actionId,
                    windowId = windowId,
                    primaryKeyCode = event.keyCode,
                    isPluginShortcut = false,
                    tabCycleMatch = match,
                )
            event.consume()
            return true
        }

        // Plugin-contributed GLOBAL shortcuts (PluginShortcutRegistry).
        val pluginActionId = findMatchingPluginDefault(event)
        if (pluginActionId != null) {
            pendingShortcut =
                PendingShortcut(
                    actionId = pluginActionId,
                    windowId = windowId,
                    primaryKeyCode = event.keyCode,
                    isPluginShortcut = true,
                    tabCycleMatch = null,
                )
            event.consume()
            return true
        }

        pendingShortcut = null
        return false // Let event propagate normally
    }

    /**
     * Uninstall the keyboard interceptor.
     * Should be called when the application exits.
     */
    fun uninstall() {
        dispatcher?.let { d ->
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(d)
        }
        dispatcher = null
        isInstalled = false
        clearPendingShortcut()
        windowIdMap.clear()
        windowContextMap.clear()
    }

    /**
     * Resolve the target AWT window for a KeyEvent.
     */
    internal fun resolveWindow(event: KeyEvent): Window? {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        if (focused != null) return focused
        val comp = event.component ?: (event.source as? java.awt.Component)
        return if (comp is Window) comp else comp?.let { javax.swing.SwingUtilities.getWindowAncestor(it) }
    }

    /**
     * Find the BOSS window ID for an AWT window, checking parent windows.
     */
    private fun findWindowId(window: Window?): String? {
        var current: Window? = window
        while (current != null) {
            val id = windowIdMap[current]
            if (id != null) return id
            current = current.owner
        }
        return null
    }

    /**
     * The physical modifier keycode that sustains an MRU tab cycle for [keystroke], mirroring
     * the platform-aware mapping in findMatchingBinding: a "Ctrl" chord is the Control key
     * on macOS but the Meta key on Windows/Linux (and vice-versa for a "Cmd" chord).
     *
     * Takes the keystroke the event MATCHED rather than the binding, so an alternate spelled
     * with the other primary modifier arms the modifier the user is actually holding. Arming
     * the wrong one wedges the switcher overlay open rather than merely losing a chord.
     */
    internal fun cyclingModifierKeyCode(keystroke: KeyStroke): Int {
        val hasCmd = "cmd" in canonicalModifiers(keystroke.modifiers)
        return if (SystemUtils.isMacOS) {
            if (hasCmd) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        } else {
            if (hasCmd) KeyEvent.VK_CONTROL else KeyEvent.VK_META
        }
    }

    /**
     * Check if a key code represents a modifier-only key.
     */
    private fun isModifierOnlyKey(keyCode: Int): Boolean =
        keyCode in
            setOf(
                KeyEvent.VK_SHIFT,
                KeyEvent.VK_CONTROL,
                KeyEvent.VK_ALT,
                KeyEvent.VK_META,
                KeyEvent.VK_CAPS_LOCK,
                KeyEvent.VK_NUM_LOCK,
                KeyEvent.VK_SCROLL_LOCK,
            )

    /**
     * Detect the current shortcut context based on:
     * 1. Explicit per-window context (set by Compose layer)
     * 2. AWT focus owner class hierarchy (fallback)
     */
    private fun detectCurrentContext(windowId: String?, event: KeyEvent? = null): ShortcutContext {
        // Primary: explicit per-window context from Compose layer
        if (windowId != null) {
            windowContextMap[windowId]?.let { return it }
        }

        // Fallback: detect from AWT focus owner's class hierarchy
        val focusOwner =
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: event?.component
                ?: (event?.source as? java.awt.Component)
        return detectContextFromAwtComponent(focusOwner)
    }

    /**
     * Detect shortcut context by walking up the AWT component hierarchy.
     * JxBrowser components have "jxbrowser" in their package name.
     */
    private fun detectContextFromAwtComponent(component: java.awt.Component?): ShortcutContext {
        var current: java.awt.Component? = component
        while (current != null) {
            val className = current.javaClass.name
            if (className.contains("jxbrowser", ignoreCase = true)) {
                return ShortcutContext.BROWSER
            }
            if (className.contains("bossterm", ignoreCase = true) ||
                className.contains("TerminalPanel", ignoreCase = false)
            ) {
                return ShortcutContext.TERMINAL
            }
            current = current.parent
        }
        return ShortcutContext.GLOBAL
    }

    /**
     * Check if a binding's context is eligible given the current active context.
     * GLOBAL and WORKSPACE bindings always match.
     * Component-specific bindings (BROWSER, TERMINAL, EDITOR) only match their context.
     */
    private fun isContextEligible(
        bindingContext: ShortcutContext,
        currentContext: ShortcutContext,
    ): Boolean =
        when (bindingContext) {
            ShortcutContext.GLOBAL -> true
            ShortcutContext.WORKSPACE -> true
            else -> bindingContext == currentContext
        }

    /** A binding, and WHICH of its keystrokes the event matched. See [cyclingModifierKeyCode]. */
    internal data class BindingMatch(
        val binding: KeyBinding,
        val keystroke: KeyStroke,
    )

    /**
     * Find a matching binding for the AWT KeyEvent.
     * Context-aware: skips component-specific bindings when the focused component
     * doesn't match, and prefers bindings whose context matches the current focus.
     *
     * Reads the keymap itself rather than taking a KeymapMatcher: it used to take one and never
     * consult it, so the caller built a matcher per keypress for nothing. The Compose-side
     * matcher is a different path (the Shortcuts tester and getMatchingBindings read it).
     */
    private fun findMatchingBinding(event: KeyEvent): BindingMatch? {
        // Canonicalised once: keyNameMatches folds both sides, so doing it per keystroke per
        // binding meant two lowercase() allocations for each of ~47 bindings per keypress.
        val eventKey = canonicalKeyName(getKeyName(event.keyCode))
        val settings = KeymapSettingsManager.currentSettings.value

        // Detect current context for filtering
        val focusedWindow = resolveWindow(event)
        val windowId = findWindowId(focusedWindow)
        val currentContext = detectCurrentContext(windowId, event)

        // Collect all matching bindings with their context priority
        var bestMatch: BindingMatch? = null
        var bestPriority = -1

        for (binding in settings.shortcuts.values) {
            if (!binding.enabled) continue

            // Primary keystroke OR any alternate - allKeystrokes is what makes Cmd+Plus reach
            // zoom in alongside Cmd+Equals. Matching only `binding.key` silently ignored every
            // alternateKeystrokes entry the model has always been able to express. WHICH
            // keystroke matched is kept, not just that one did: the MRU cycle arms on its
            // modifier, and for an alternate the binding's primary is the wrong answer.
            val matched =
                binding.allKeystrokes.firstOrNull { keystroke ->
                    canonicalKeyName(keystroke.key) == eventKey && chordMatchesEvent(keystroke.modifiers, event)
                }

            if (matched != null) {
                // Skip bindings whose context doesn't match
                if (!isContextEligible(binding.context, currentContext)) continue

                // Prioritize: exact context match > GLOBAL > WORKSPACE
                val priority =
                    when {
                        binding.context == currentContext -> 3
                        binding.context == ShortcutContext.GLOBAL -> 2
                        binding.context == ShortcutContext.WORKSPACE -> 1
                        else -> 0
                    }

                if (priority > bestPriority) {
                    bestMatch = BindingMatch(binding, matched)
                    bestPriority = priority
                }
            }
        }

        return bestMatch
    }

    /**
     * Match the event against plugin shortcut DEFAULT bindings. Only specs
     * whose actionId has no entry in the keymap settings participate — a user
     * rebind (or explicit unbind) always supersedes the plugin default. Note
     * the dispatcher's early modifier gate applies: plugin defaults must
     * include Cmd/Ctrl/Alt to be reachable.
     */
    private fun findMatchingPluginDefault(event: KeyEvent): String? {
        val pluginShortcuts = PluginShortcutRegistryImpl.shortcuts.value
        if (pluginShortcuts.isEmpty()) return null

        val eventKey = canonicalKeyName(getKeyName(event.keyCode))
        val userShortcuts = KeymapSettingsManager.currentSettings.value.shortcuts

        for (registered in pluginShortcuts) {
            val spec = registered.spec
            val default = spec.defaultBinding ?: continue
            if (userShortcuts.containsKey(spec.actionId)) continue
            if (canonicalKeyName(default.key) != eventKey) continue
            if (chordMatchesEvent(default.modifiers, event)) {
                return spec.actionId
            }
        }
        return null
    }

    /**
     * Platform-aware modifier match shared by the host-binding pass and the
     * plugin-default pass, so both agree on Cmd/Ctrl handling (on non-mac,
     * "Cmd" maps to the Control key). [modifiers] is the binding's modifier
     * name list; the caller has already matched the key.
     */
    private fun chordMatchesEvent(
        modifiers: Collection<String>,
        event: KeyEvent,
    ): Boolean {
        val canonical = canonicalModifiers(modifiers.toList())
        val hasCmd = "cmd" in canonical
        val hasCtrl = "ctrl" in canonical
        val hasShift = "shift" in canonical
        val hasAlt = "alt" in canonical

        val primaryMatch =
            if (hasCmd || hasCtrl) {
                if (SystemUtils.isMacOS) {
                    (hasCmd && event.isMetaDown) || (hasCtrl && event.isControlDown)
                } else {
                    (hasCmd && event.isControlDown) || (hasCtrl && event.isMetaDown)
                }
            } else {
                !event.isMetaDown && !event.isControlDown
            }
        return primaryMatch && hasShift == event.isShiftDown && hasAlt == event.isAltDown
    }

    /**
     * Compare a binding's key name against [eventKeyName] from [getKeyName].
     *
     * Alias-tolerant so a keymap file written by an older build (or hand-edited, or imported
     * from another machine) still matches: "Left" and "DirectionLeft" are the same key, as are
     * "Space"/"Spacebar" and "Esc"/"Escape".
     */
    internal fun keyNameMatches(
        bindingKey: String,
        eventKeyName: String,
    ): Boolean = canonicalKeyName(bindingKey) == canonicalKeyName(eventKeyName)

    /**
     * The key-name fold, delegated to the model so this path and the Compose matcher cannot
     * drift again. This file used to own a copy, which is how "Left" and "DirectionLeft"
     * managed to be different keys on one path and the same on the other.
     */
    private fun canonicalKeyName(keyName: String): String =
        ai.rever.boss.keymap.model
            .canonicalKeyName(keyName)

    /**
     * Convert AWT key code to key name string.
     *
     * The vocabulary here has to be the one the presets store, which is Compose's `Key` naming:
     * the arrows are "DirectionLeft" and friends, NOT "Left". They used to be spelled "Left",
     * which meant no arrow binding ever matched on this path - Cmd+Arrow panel navigation
     * worked only because the native menu carries its own accelerator, and fell dead the moment
     * a terminal or browser held focus. [keyNameMatches] accepts both spellings so an older or
     * hand-edited keymap file still resolves.
     *
     * `internal` so `KeyVocabularyAgreementTest` can hold this table against the names Compose
     * renders for the same physical keys. The two are separate hand-maintained vocabularies over
     * one keyboard, and every time they have drifted the symptom has been a chord that works on
     * one path and silently does nothing on the other.
     */
    internal fun getKeyName(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.VK_A -> "A"
            KeyEvent.VK_B -> "B"
            KeyEvent.VK_C -> "C"
            KeyEvent.VK_D -> "D"
            KeyEvent.VK_E -> "E"
            KeyEvent.VK_F -> "F"
            KeyEvent.VK_G -> "G"
            KeyEvent.VK_H -> "H"
            KeyEvent.VK_I -> "I"
            KeyEvent.VK_J -> "J"
            KeyEvent.VK_K -> "K"
            KeyEvent.VK_L -> "L"
            KeyEvent.VK_M -> "M"
            KeyEvent.VK_N -> "N"
            KeyEvent.VK_O -> "O"
            KeyEvent.VK_P -> "P"
            KeyEvent.VK_Q -> "Q"
            KeyEvent.VK_R -> "R"
            KeyEvent.VK_S -> "S"
            KeyEvent.VK_T -> "T"
            KeyEvent.VK_U -> "U"
            KeyEvent.VK_V -> "V"
            KeyEvent.VK_W -> "W"
            KeyEvent.VK_X -> "X"
            KeyEvent.VK_Y -> "Y"
            KeyEvent.VK_Z -> "Z"
            KeyEvent.VK_0 -> "Zero"
            KeyEvent.VK_1 -> "One"
            KeyEvent.VK_2 -> "Two"
            KeyEvent.VK_3 -> "Three"
            KeyEvent.VK_4 -> "Four"
            KeyEvent.VK_5 -> "Five"
            KeyEvent.VK_6 -> "Six"
            KeyEvent.VK_7 -> "Seven"
            KeyEvent.VK_8 -> "Eight"
            KeyEvent.VK_9 -> "Nine"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_ESCAPE -> "Esc"
            KeyEvent.VK_SPACE -> "Space"
            KeyEvent.VK_TAB -> "Tab"
            KeyEvent.VK_BACK_SPACE -> "Backspace"
            KeyEvent.VK_DELETE -> "Delete"
            KeyEvent.VK_LEFT -> "DirectionLeft"
            KeyEvent.VK_RIGHT -> "DirectionRight"
            KeyEvent.VK_UP -> "DirectionUp"
            KeyEvent.VK_DOWN -> "DirectionDown"
            KeyEvent.VK_HOME -> "Home"
            KeyEvent.VK_END -> "End"
            KeyEvent.VK_PAGE_UP -> "PageUp"
            KeyEvent.VK_PAGE_DOWN -> "PageDown"
            KeyEvent.VK_F1 -> "F1"
            KeyEvent.VK_F2 -> "F2"
            KeyEvent.VK_F3 -> "F3"
            KeyEvent.VK_F4 -> "F4"
            KeyEvent.VK_F5 -> "F5"
            KeyEvent.VK_F6 -> "F6"
            KeyEvent.VK_F7 -> "F7"
            KeyEvent.VK_F8 -> "F8"
            KeyEvent.VK_F9 -> "F9"
            KeyEvent.VK_F10 -> "F10"
            KeyEvent.VK_F11 -> "F11"
            KeyEvent.VK_F12 -> "F12"
            KeyEvent.VK_MINUS -> "Minus"
            KeyEvent.VK_EQUALS -> "Equals"
            KeyEvent.VK_PLUS -> "Plus"
            KeyEvent.VK_OPEN_BRACKET -> "OpenBracket"
            KeyEvent.VK_CLOSE_BRACKET -> "CloseBracket"
            KeyEvent.VK_SLASH -> "Slash"
            KeyEvent.VK_BACK_SLASH -> "Backslash"
            KeyEvent.VK_SEMICOLON -> "Semicolon"
            KeyEvent.VK_QUOTE -> "Apostrophe"
            KeyEvent.VK_COMMA -> "Comma"
            KeyEvent.VK_PERIOD -> "Period"
            KeyEvent.VK_BACK_QUOTE -> "Grave"
            else -> KeyEvent.getKeyText(keyCode)
        }

    /**
     * Run [trigger] and claim the event, but only while [windowId]'s active panel has more than
     * one tab. Returning false leaves the chord to the focused component.
     */
    internal fun dispatchIfCanStepTabs(
        windowId: String,
        trigger: (String) -> Unit,
    ): Boolean {
        if (!MenuActionsHandler.canStepTabs(windowId)) return false
        trigger(windowId)
        return true
    }

    /**
     * Run [trigger] and claim the event, but only while [windowId]'s active panel actually has a
     * tab at position [index].
     *
     * `selectTabByPosition` ignores an out-of-range position, so without this a two-tab window
     * would swallow Cmd+3 through Cmd+8 from a terminal or an editor and do nothing with them -
     * the same "claiming a chord that cannot act" this file gates panel navigation and tab
     * stepping against. Browsers do consume the whole Cmd+1..9 block unconditionally; BOSS does
     * not, because those chords reach surfaces a browser has no equivalent of.
     */
    internal fun dispatchIfTabExistsAt(
        windowId: String,
        index: Int,
        trigger: (String) -> Unit,
    ): Boolean {
        if (MenuActionsHandler.activePanelTabCount(windowId) <= index) return false
        trigger(windowId)
        return true
    }

    /**
     * Run [trigger] and claim the event, but only while [windowId] has more than one panel.
     *
     * Returning false leaves the chord to the focused component. See the panel-navigation
     * branches in [dispatchAction] for why that matters.
     */
    internal fun dispatchIfMultiPanel(
        windowId: String,
        trigger: (String) -> Unit,
    ): Boolean {
        val panelCount = MenuActionsHandler.panelCountState.value[windowId] ?: 1
        if (panelCount <= 1) return false
        trigger(windowId)
        return true
    }

    /**
     * Check whether [actionId] can currently be handled by [dispatchAction] for [windowId].
     * Used during KEY_PRESSED to verify if the interceptor will claim the chord on release.
     */
    internal fun canDispatchAction(
        actionId: String,
        windowId: String,
    ): Boolean =
        when (actionId) {
            KeymapActions.TAB_NEW,
            KeymapActions.TAB_CLOSE,
            KeymapActions.TAB_NEXT,
            KeymapActions.TAB_PREVIOUS,
            KeymapActions.WINDOW_NEW,
            KeymapActions.WINDOW_CLOSE,
            KeymapActions.BROWSER_ZOOM_IN,
            KeymapActions.BROWSER_ZOOM_OUT,
            KeymapActions.BROWSER_ZOOM_RESET,
            KeymapActions.FOCUS_MODE_TOGGLE,
            KeymapActions.CHROME_DENSITY_CYCLE,
            KeymapActions.PANEL_SPLIT_VERTICAL,
            KeymapActions.PANEL_SPLIT_HORIZONTAL,
            KeymapActions.BROWSER_RELOAD,
            KeymapActions.BROWSER_FIND,
            KeymapActions.BROWSER_BACK,
            KeymapActions.BROWSER_FORWARD,
            KeymapActions.BROWSER_DEVTOOLS,
            KeymapActions.CODEBASE_OPEN,
            KeymapActions.GLOBAL_SEARCH_OPEN,
            KeymapActions.SETTINGS_OPEN,
            KeymapActions.WORKSPACE_SAVE,
            KeymapActions.HELP_SHORTCUTS -> true

            KeymapActions.TAB_REOPEN_CLOSED -> ClosedTabHistory.hasEntries(windowId)

            KeymapActions.TAB_NEXT_POSITIONAL,
            KeymapActions.TAB_PREVIOUS_POSITIONAL -> MenuActionsHandler.canStepTabs(windowId)

            KeymapActions.TAB_SELECT_LAST -> MenuActionsHandler.activePanelTabCount(windowId) > 0

            KeymapActions.PANEL_NAVIGATE_LEFT,
            KeymapActions.PANEL_NAVIGATE_RIGHT,
            KeymapActions.PANEL_NAVIGATE_UP,
            KeymapActions.PANEL_NAVIGATE_DOWN -> (MenuActionsHandler.panelCountState.value[windowId] ?: 1) > 1

            else -> {
                val tabIndex = KeymapActions.TAB_SELECT_BY_INDEX.indexOf(actionId)
                if (tabIndex >= 0) {
                    MenuActionsHandler.activePanelTabCount(windowId) > tabIndex
                } else {
                    actionId.startsWith(PluginShortcutRegistryImpl.ACTION_ID_PREFIX)
                }
            }
        }

    /**
     * Dispatch an action through MenuActionsHandler.
     * Returns true if the action was handled, false otherwise.
     *
     * Internal rather than private so desktopTest can assert which actions the interceptor
     * claims: returning false is load-bearing, because it is what leaves a chord to the
     * component that really serves it.
     */
    internal fun dispatchAction(
        actionId: String,
        windowId: String,
    ): Boolean =
        when (actionId) {
            // Tab Management
            KeymapActions.TAB_NEW -> {
                MenuActionsHandler.triggerNewTab(windowId)
                true
            }

            KeymapActions.TAB_CLOSE -> {
                MenuActionsHandler.triggerCloseTab(windowId)
                true
            }

            KeymapActions.TAB_NEXT -> {
                MenuActionsHandler.triggerNextTab(windowId)
                true
            }

            KeymapActions.TAB_PREVIOUS -> {
                MenuActionsHandler.triggerPreviousTab(windowId)
                true
            }

            // Gated on the same state as the File menu item's enabled flag: with an empty
            // stack the chord would be consumed for nothing, and Cmd+Shift+T is a plain
            // Cmd+Shift+letter that another surface may well want.
            KeymapActions.TAB_REOPEN_CLOSED -> {
                if (!ClosedTabHistory.hasEntries(windowId)) {
                    false
                } else {
                    MenuActionsHandler.triggerReopenClosedTab(windowId)
                    true
                }
            }

            // Gated for the same reason as panel navigation below: with one tab there is
            // nowhere to step, and claiming the chord would take Cmd+Shift+Bracket away from an
            // editor (where the VS Code and IntelliJ presets put these) for no effect.
            KeymapActions.TAB_NEXT_POSITIONAL -> {
                dispatchIfCanStepTabs(windowId) { MenuActionsHandler.triggerNextTabPositional(it) }
            }

            KeymapActions.TAB_PREVIOUS_POSITIONAL -> {
                dispatchIfCanStepTabs(windowId) { MenuActionsHandler.triggerPreviousTabPositional(it) }
            }

            // Index 0, not 8: Cmd+9 means "the last tab", so any non-empty panel serves it. In a
            // one-tab panel it is claimed and reselects the already-active tab, which is what
            // browsers do; only an empty panel lets the chord through.
            KeymapActions.TAB_SELECT_LAST -> {
                dispatchIfTabExistsAt(windowId, 0) { MenuActionsHandler.triggerSelectLastTab(it) }
            }

            // Window Management
            KeymapActions.WINDOW_NEW -> {
                WindowOperations.createNewWindow()
                true
            }

            KeymapActions.WINDOW_CLOSE -> {
                WindowOperations.closeWindow(windowId)
                true
            }

            // Browser Controls (Zoom)
            KeymapActions.BROWSER_ZOOM_IN -> {
                MenuActionsHandler.triggerZoomIn(windowId)
                true
            }

            KeymapActions.BROWSER_ZOOM_OUT -> {
                MenuActionsHandler.triggerZoomOut(windowId)
                true
            }

            KeymapActions.BROWSER_ZOOM_RESET -> {
                MenuActionsHandler.triggerActualSize(windowId)
                true
            }

            // View Controls
            KeymapActions.FOCUS_MODE_TOGGLE -> {
                MenuActionsHandler.triggerToggleFocusMode(windowId)
                true
            }

            KeymapActions.CHROME_DENSITY_CYCLE -> {
                MenuActionsHandler.triggerChromeDensityCycle(windowId)
                true
            }

            // Panel Navigation.
            //
            // Gated on there being somewhere to navigate TO, mirroring the `enabled` flag on the
            // matching View-menu items. The gate is what keeps Cmd+Left meaning "caret to line
            // start" in a text field or a web page whenever the window has a single panel: the
            // default bindings are bare Cmd+Arrow, which macOS also reserves for caret movement,
            // so an unconditional `true` here would consume the chord and hand back nothing.
            // (With a split open the chord is the user's panel navigation either way - that is
            // already what the enabled menu accelerator does today.)
            KeymapActions.PANEL_NAVIGATE_LEFT -> {
                dispatchIfMultiPanel(windowId) { MenuActionsHandler.triggerNavigatePanelLeft(it) }
            }

            KeymapActions.PANEL_NAVIGATE_RIGHT -> {
                dispatchIfMultiPanel(windowId) { MenuActionsHandler.triggerNavigatePanelRight(it) }
            }

            KeymapActions.PANEL_NAVIGATE_UP -> {
                dispatchIfMultiPanel(windowId) { MenuActionsHandler.triggerNavigatePanelUp(it) }
            }

            KeymapActions.PANEL_NAVIGATE_DOWN -> {
                dispatchIfMultiPanel(windowId) { MenuActionsHandler.triggerNavigatePanelDown(it) }
            }

            // Split Panel
            KeymapActions.PANEL_SPLIT_VERTICAL -> {
                MenuActionsHandler.triggerSplitVertically(windowId)
                true
            }

            KeymapActions.PANEL_SPLIT_HORIZONTAL -> {
                MenuActionsHandler.triggerSplitHorizontally(windowId)
                true
            }

            // Browser Controls
            KeymapActions.BROWSER_RELOAD -> {
                MenuActionsHandler.triggerReloadBrowser(windowId)
                true
            }

            KeymapActions.BROWSER_FIND -> {
                MenuActionsHandler.triggerBrowserFind(windowId)
                true
            }

            // These three claim unconditionally while every neighbouring branch carries a gate,
            // and that is deliberate: their bindings are ShortcutContext.BROWSER, so
            // isContextEligible(BROWSER, GLOBAL) is false and findMatchingBinding only reaches
            // here when the focus walk already reported BROWSER. The gate is upstream and
            // stronger than a dispatch-time count, not missing. (The MENU items for the same
            // actions do need `enabled`, because an accelerator fires window-wide whatever the
            // context - see ActiveBrowserRegistry.windowsWithActiveBrowser.)
            KeymapActions.BROWSER_BACK -> {
                MenuActionsHandler.triggerBrowserBack(windowId)
                true
            }

            KeymapActions.BROWSER_FORWARD -> {
                MenuActionsHandler.triggerBrowserForward(windowId)
                true
            }

            KeymapActions.BROWSER_DEVTOOLS -> {
                MenuActionsHandler.triggerBrowserDevTools(windowId)
                true
            }

            // Codebase
            KeymapActions.CODEBASE_OPEN -> {
                MenuActionsHandler.triggerOpenCodebase(windowId)
                true
            }

            // Global Search
            KeymapActions.GLOBAL_SEARCH_OPEN -> {
                MenuActionsHandler.triggerOpenGlobalSearch(windowId)
                true
            }

            // Settings
            KeymapActions.SETTINGS_OPEN -> {
                MenuActionsHandler.triggerOpenSettings(windowId)
                true
            }

            // Workspace
            KeymapActions.WORKSPACE_SAVE -> {
                MenuActionsHandler.triggerSaveWorkspace(windowId)
                true
            }

            // Help
            KeymapActions.HELP_SHORTCUTS -> {
                MenuActionsHandler.triggerShowShortcutHelp(windowId)
                true
            }

            else -> {
                // Cmd+1..Cmd+8 carry a position, so they resolve by lookup rather than as
                // eight near-identical branches.
                val tabIndex = KeymapActions.TAB_SELECT_BY_INDEX.indexOf(actionId)
                when {
                    tabIndex >= 0 -> {
                        dispatchIfTabExistsAt(windowId, tabIndex) {
                            MenuActionsHandler.triggerSelectTabByIndex(it, tabIndex)
                        }
                    }

                    // Plugin-contributed actions ("plugin.<pluginId>.<name>") -
                    // reached when the user rebound a plugin shortcut (the binding
                    // then lives in the keymap settings and matches the main pass).
                    actionId.startsWith(PluginShortcutRegistryImpl.ACTION_ID_PREFIX) -> {
                        PluginShortcutRegistryImpl.dispatch(actionId, windowId)
                    }

                    else -> {
                        false
                    }
                }
            }
        }
}
