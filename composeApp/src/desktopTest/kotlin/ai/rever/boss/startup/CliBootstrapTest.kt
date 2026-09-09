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
    @Test
    fun failedForwardDoesNotSkipRemainingLinks() {
        val attempted = mutableListOf<String>()
        val links = arrayOf("https://one.example", "https://two.example", "https://three.example")
        val result = CliBootstrap.forwardToExistingInstance(links, maxRetries = 1) { link, origin ->
            assertEquals(ai.rever.boss.utils.DeepLinkOrigin.EXTERNAL, origin)
            attempted.add(link)
            attempted.size != 1
        }
        assertFalse(result)
        assertEquals(3, attempted.size)
        assertTrue(attempted[0].contains("one.example"))
        assertTrue(attempted[2].contains("three.example"))
    }

    @Test
    fun headlessFlagsDoNotConsumeGuiArguments() {
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf("--help", "extra")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("-v", "status")))
    }

}
