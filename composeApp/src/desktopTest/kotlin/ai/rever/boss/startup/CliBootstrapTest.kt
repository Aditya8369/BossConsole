package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliBootstrapTest {
    @Test
    fun isHeadlessCliRecognizesStandardCommands() {
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("status")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("STATUS")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("mcp")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("completion")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("--help")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("-h")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("-h", "--help")))
    }

    @Test
    fun isHeadlessCliReturnsFalseForGuiAndOtherArgs() {
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf()))
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf("boss://auth/callback")))
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf("C:\\path\\to\\file.txt")))
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf("run", "something")))
    }

    @Test
    fun handleProtocolUnregistrationReturnsContinueWhenFlagAbsent() {
        val result = CliBootstrap.handleProtocolUnregistration(arrayOf("status"))
        assertEquals(CliDispatchResult.Continue, result)
    }
}
