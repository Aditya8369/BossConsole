package ai.rever.boss.startup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartupNoticeQueueTest {
    @Test
    fun `notice survives a slow cold start and is consumed by only one window`() = runTest {
        val queue = StartupNoticeQueue()
        queue.report("missing auth")
        delay(60_000)
        assertEquals("missing auth", queue.notices.first())
        assertNull(withTimeoutOrNull(100) { queue.notices.first() })
    }
}
