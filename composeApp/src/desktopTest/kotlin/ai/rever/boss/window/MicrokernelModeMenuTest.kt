package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the Process / Microkernel Mode menu label selection logic.
 *
 * Verifies that the restart-required status outranks the save-failed status so that
 * a pending restart state is never masked by a previous save error.
 */
class MicrokernelModeMenuTest {

    @Test
    fun `when restart is required and save failed, label prioritizes restart required over save failed`() {
        val suffix = microkernelModeLabelSuffix(isRestartRequired = true, modeSaveFailed = true)
        val label = microkernelModeItemLabel(isRestartRequired = true, modeSaveFailed = true)

        assertEquals(" (restart required)", suffix)
        assertEquals("Microkernel Mode (restart required)", label)
        assertTrue(label.contains("(restart required)"))
        assertFalse(label.contains("(save failed)"))
    }

    @Test
    fun `when restart is required and save succeeded, label displays restart required`() {
        val suffix = microkernelModeLabelSuffix(isRestartRequired = true, modeSaveFailed = false)
        val label = microkernelModeItemLabel(isRestartRequired = true, modeSaveFailed = false)

        assertEquals(" (restart required)", suffix)
        assertEquals("Microkernel Mode (restart required)", label)
    }

    @Test
    fun `when restart is not required and save failed, label displays save failed`() {
        val suffix = microkernelModeLabelSuffix(isRestartRequired = false, modeSaveFailed = true)
        val label = microkernelModeItemLabel(isRestartRequired = false, modeSaveFailed = true)

        assertEquals(" (save failed)", suffix)
        assertEquals("Microkernel Mode (save failed)", label)
        assertTrue(label.contains("(save failed)"))
        assertFalse(label.contains("(restart required)"))
    }

    @Test
    fun `when restart is not required and save succeeded, label has no suffix`() {
        val suffix = microkernelModeLabelSuffix(isRestartRequired = false, modeSaveFailed = false)
        val label = microkernelModeItemLabel(isRestartRequired = false, modeSaveFailed = false)

        assertEquals("", suffix)
        assertEquals("Microkernel Mode", label)
        assertFalse(label.contains("("))
    }
}
