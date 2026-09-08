package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * BossConsole#110: `SubmitResult.Error` used to hold a raw exception string, sanitized only at
 * one render call site in [CrashReportDialog]. `Error`'s constructor is now private -
 * [CrashReportService.SubmitResult.Error.of] is the only way to build one, and it sanitizes
 * before the raw string can ever reach `.message` - so the guarantee is a property of the type,
 * not something every future consumer (a copy button, a toast, a log line) has to remember
 * independently.
 */
class CrashReportServiceTest {
    @Test
    fun `the factory sanitizes a bare hostname at construction, not just at render`() {
        val error =
            CrashReportService.SubmitResult.Error.of(
                "Failed to submit crash report: proxy.corp.internal",
            )

        assertFalse(error.message.contains("proxy.corp.internal"), "hostname leaked: ${error.message}")
    }

    @Test
    fun `the factory preserves the diagnostic half of the message`() {
        val error = CrashReportService.SubmitResult.Error.of("Request timeout has expired")

        assertEquals("Request timeout has expired", error.message)
    }

    @Test
    fun `the factory turns a blank message into the placeholder`() {
        val error = CrashReportService.SubmitResult.Error.of("")

        assertEquals("[no message]", error.message)
    }
}
