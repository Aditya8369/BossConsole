package ai.rever.boss.kernel

import ai.rever.boss.config.kernelModeAvailable
import kotlin.test.Test
import kotlin.test.assertTrue

/** This source set is excluded alongside the kernel on unsupported Windows ARM64 builds. */
class KernelAvailabilityTest {
    @Test
    fun `build including kernel classes exposes process mode`() {
        assertTrue(kernelModeAvailable())
    }
}
