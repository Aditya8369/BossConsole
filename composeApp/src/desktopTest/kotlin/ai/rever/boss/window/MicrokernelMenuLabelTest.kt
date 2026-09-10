package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals

class MicrokernelMenuLabelTest {
    @Test
    fun `loading state outranks all other conditions`() {
        assertEquals(
            "Microkernel Mode (loading)",
            microkernelMenuLabel(
                loaded = false,
                available = false,
                overrideSource = "BOSS_MODE env",
                restartRequired = true,
                saveFailed = true,
            ),
        )
        assertEquals(
            "Microkernel Mode (loading)",
            microkernelMenuLabel(
                loaded = false,
                available = true,
                overrideSource = null,
                restartRequired = false,
                saveFailed = false,
            ),
        )
    }

    @Test
    fun `unavailable build outranks override restart and save failure`() {
        assertEquals(
            "Microkernel Mode (unavailable)",
            microkernelMenuLabel(
                loaded = true,
                available = false,
                overrideSource = "BOSS_MODE env",
                restartRequired = true,
                saveFailed = true,
            ),
        )
        assertEquals(
            "Microkernel Mode (unavailable)",
            microkernelMenuLabel(
                loaded = true,
                available = false,
                overrideSource = null,
                restartRequired = false,
                saveFailed = false,
            ),
        )
    }

    @Test
    fun `external override outranks restart and save failure`() {
        val override = "BOSS_MODE environment variable"
        assertEquals(
            "Microkernel Mode (externally controlled)",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = override,
                restartRequired = true,
                saveFailed = true,
            ),
        )
        assertEquals(
            "Microkernel Mode (externally controlled)",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = override,
                restartRequired = false,
                saveFailed = false,
            ),
        )
    }

    @Test
    fun `restart required outranks retained save failure`() {
        // A failed save in this window followed by a successful save in Settings or another window
        // must display restart required rather than masking the pending restart with the failure flag.
        assertEquals(
            "Microkernel Mode (restart required)",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = null,
                restartRequired = true,
                saveFailed = true,
            ),
        )
        assertEquals(
            "Microkernel Mode (restart required)",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = null,
                restartRequired = true,
                saveFailed = false,
            ),
        )
    }

    @Test
    fun `save failure displays when no restart is pending`() {
        assertEquals(
            "Microkernel Mode (save failed)",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = null,
                restartRequired = false,
                saveFailed = true,
            ),
        )
    }

    @Test
    fun `default state displays clean label`() {
        assertEquals(
            "Microkernel Mode",
            microkernelMenuLabel(
                loaded = true,
                available = true,
                overrideSource = null,
                restartRequired = false,
                saveFailed = false,
            ),
        )
    }
}
