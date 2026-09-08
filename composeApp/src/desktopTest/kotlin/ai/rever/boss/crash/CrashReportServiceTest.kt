package ai.rever.boss.crash

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#110: `SubmitResult.Error` used to hold a raw exception string, sanitized only at
 * one render call site in [CrashReportDialog]. `Error`'s constructor is now private -
 * [CrashReportService.SubmitResult.Error] is the only way to build one, and it sanitizes
 * before the raw string can ever reach `.message` - so the guarantee is a property of the type,
 * not something every future consumer (a copy button, a toast, a log line) has to remember
 * independently.
 */
class CrashReportServiceTest {
    @Test
    fun `the constructor and generated copy cannot bypass the factory`() {
        val errorClass = CrashReportService.SubmitResult.Error::class.java

        assertTrue(Modifier.isPrivate(errorClass.getDeclaredConstructor(String::class.java).modifiers))
        assertTrue(Modifier.isPrivate(errorClass.getDeclaredMethod("copy", String::class.java).modifiers))
    }

    @Test
    fun `the factory sanitizes a URL at construction, not just at render`() {
        val error =
            CrashReportService.SubmitResult.Error(
                "Failed to submit crash report: https://proxy.corp.internal/report",
            )

        assertFalse(error.message.contains("proxy.corp.internal"), "URL hostname leaked: ${error.message}")
    }

    @Test
    fun `the factory preserves the diagnostic half of the message`() {
        val error = CrashReportService.SubmitResult.Error("Request timeout has expired")

        assertEquals("Request timeout has expired", error.message)
    }

    @Test
    fun `the factory turns a blank message into the placeholder`() {
        val error = CrashReportService.SubmitResult.Error("")

        assertEquals("[no message]", error.message)
    }
}
