package ai.rever.boss.startup

import ai.rever.boss.cli.configureHeadlessLogging
import ai.rever.boss.cli.createBossCLI
import ai.rever.boss.llm.RisaLlmTokenCommand
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.OsOpenArguments
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.WindowsProtocolHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Result of early / headless CLI dispatch before GUI initialization.
 */
sealed interface CliDispatchResult {
    data class Exit(val code: Int) : CliDispatchResult
    data object Continue : CliDispatchResult
}

/**
 * Encapsulates early CLI handling, headless command dispatch, installer action dispatch,
 * single-instance link forwarding, and post-lock CLI argument processing.
 */
object CliBootstrap {
    private val logger by lazy { BossLogger.forComponent("CliBootstrap") }

    /**
     * Determines whether the given CLI arguments represent a headless command that must
     * execute without booting the GUI.
     */
    fun isHeadlessCli(args: Array<String>): Boolean {
        val firstNonFlag = args.firstOrNull { !it.startsWith("-") }?.lowercase()
        return firstNonFlag in setOf("status", "mcp", "completion") ||
            (args.isNotEmpty() && args.all { it in setOf("-h", "--help") })
    }

    /**
     * Dispatches headless CLI commands before AWT, logging, plugins, or single-instance locks.
     *
     * Returns [CliDispatchResult.Exit] if the process should terminate immediately with an exit code,
     * or [CliDispatchResult.Continue] if GUI bootstrap should proceed.
     */
    fun dispatchHeadless(args: Array<String>): CliDispatchResult {
        // Codex invokes this headless credential helper. Handle it before AWT,
        // plugins, logging, or the single-instance lock so stdout stays token-only.
        if (RisaLlmTokenCommand.isRequested(args)) {
            return CliDispatchResult.Exit(RisaLlmTokenCommand.execute())
        }

        // Headless CLI commands (status, mcp, completion, --help) target the running
        // instance or generate output headlessly. Execute before AWT, plugins, Skiko,
        // or acquiring the single-instance lock so they fail without GUI startup when BOSS is
        // closed without booting the GUI or corrupting standard output streams.
        if (isHeadlessCli(args)) {
            configureHeadlessLogging()
            return try {
                createBossCLI().main(args)
                CliDispatchResult.Exit(0)
            } catch (e: ProgramResult) {
                CliDispatchResult.Exit(e.statusCode)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message ?: "Failed to execute CLI command"}")
                CliDispatchResult.Exit(1)
            }
        }

        return CliDispatchResult.Continue
    }

    /**
     * Handles early protocol unregistration flag before single-instance lock or window creation.
     */
    fun handleProtocolUnregistration(args: Array<String>): CliDispatchResult {
        if (args.contains("--unregister-protocol")) {
            return CliDispatchResult.Exit(WindowsProtocolHandler.unregisterProtocolExitCode())
        }
        return CliDispatchResult.Continue
    }

    /**
     * Forwards open requests to an already running instance when single-instance lock acquisition fails.
     * Returns true if all requests were successfully forwarded or there were no URLs to forward.
     */
    fun forwardToExistingInstance(args: Array<String>, maxRetries: Int = 3): Boolean {
        val deepLinks = OsOpenArguments.deepLinksFrom(args)
        if (deepLinks.isEmpty()) {
            logger.info(LogCategory.SYSTEM, "No URL to send - existing BOSS window should be visible")
            return true
        }

        logger.info(
            LogCategory.SYSTEM,
            "Sending open requests to existing instance",
            mapOf("count" to deepLinks.size),
        )

        fun forward(link: String): Boolean {
            for (attempt in 1..maxRetries) {
                if (SingleInstanceManager.sendToExistingInstance(link, DeepLinkOrigin.EXTERNAL)) {
                    logger.info(LogCategory.SYSTEM, "URL sent successfully", mapOf("attempt" to attempt))
                    return true
                }
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to send URL",
                    mapOf(
                        "attempt" to attempt,
                        "maxRetries" to maxRetries,
                    ),
                )
                if (attempt < maxRetries) {
                    runBlocking {
                        delay(500)
                    }
                }
            }
            return false
        }

        val success = deepLinks.fold(true) { acc, link -> forward(link) && acc }
        if (!success) {
            logger.error(
                LogCategory.SYSTEM,
                "Could not send URL to existing instance after retries",
                mapOf("maxRetries" to maxRetries),
            )
        }
        return success
    }

    /**
     * Dispatches CLI arguments after the single-instance lock has been acquired.
     */
    fun dispatchPostLock(args: Array<String>) {
        if (args.isNotEmpty()) {
            try {
                val osOpenRequests = OsOpenArguments.deepLinksFrom(args)
                if (osOpenRequests.isEmpty()) {
                    logger.debug(LogCategory.SYSTEM, "Processing CLI arguments", mapOf("args" to args.joinToString(" ")))
                    createBossCLI().main(args)
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "CLI error", error = e)
            }
        }

        DeepLinkHandler.processCommandLineArgs(args)
    }
}
