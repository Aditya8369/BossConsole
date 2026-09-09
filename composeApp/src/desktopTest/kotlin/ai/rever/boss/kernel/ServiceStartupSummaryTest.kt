package ai.rever.boss.kernel

import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceStartupSummaryTest {
    @Test
    fun `partial startup retains missing and failed services in the same notice`() {
        val summary = serviceStartupSummary(2, listOf("auth"), listOf("editor"))
        assertTrue(summary.contains("2 service(s) spawned"))
        assertTrue(summary.contains("Missing JARs: auth"))
        assertTrue(summary.contains("Failed to spawn: editor"))
        assertTrue(summary.contains("readiness not verified"))
    }
}
