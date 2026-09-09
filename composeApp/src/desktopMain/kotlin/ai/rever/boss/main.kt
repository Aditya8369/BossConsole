package ai.rever.boss

import BossTheme
import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.ChromiumDownloadContent
import ai.rever.boss.components.settings.search.SettingsSearchIndex
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.config.ChromiumFlagKeys
import ai.rever.boss.config.ChromiumFlagsSettingsManager
import ai.rever.boss.config.ConfigLoader
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.crash.CrashHandler
import ai.rever.boss.crash.RENDER_RECOVERY_TOAST_MILLIS
import ai.rever.boss.crash.RenderCrashPolicy
import ai.rever.boss.crash.RenderRecoveryToaster
import ai.rever.boss.crash.WindowExceptionRoute
import ai.rever.boss.crash.decideWindowExceptionRoute
import ai.rever.boss.crash.displayPluginId
import ai.rever.boss.crash.hasFatalCause
import ai.rever.boss.crash.hostPluginIdResolver
import ai.rever.boss.crash.noteRecoveryOutcome
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.performance.MemoryPressureWatchdog
import ai.rever.boss.performance.PerformanceMonitor
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import ai.rever.boss.plugin.sandbox.ui.installCrashInterceptor
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.services.passkey.PasskeyPlatformInit
import ai.rever.boss.startup.CliBootstrap
import ai.rever.boss.startup.CliDispatchResult
import ai.rever.boss.startup.ChromiumBootstrap
import ai.rever.boss.startup.OverlaySetup
import ai.rever.boss.startup.PlatformSetup
import ai.rever.boss.startup.ShutdownSequence
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.updater.AppUpdateRealtimeService
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.AWTKeyboardInterceptor
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindow
import ai.rever.boss.window.BossWindowIcon
import ai.rever.boss.window.DefaultWindowIcon
import ai.rever.boss.window.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Window
import javax.swing.JPopupMenu
import kotlin.system.exitProcess

private val logger by lazy { BossLogger.forComponent("Main") }

/**
 * Decides the render-recovery toast and rate-limits it. EDT-confined: the window
 * exception handler is the only caller. See [RenderRecoveryToaster] for why this
 * is not just a `!=` against the last message.
 */
private val renderRecoveryToaster = RenderRecoveryToaster()

/**
 * A fault we can pin on a plugin that has no boundary to hand it to.
 *
 * The gap this closes: [PluginCrashInterceptor.attributeToPlugin] only answers
 * for plugins with a *mounted* error boundary, so a plugin with no UI on screen
 * was unattributable — and an unattributable `StackOverflowError` escalated to
 * ending the app.
 */
private fun quarantineBlamedPlugin(
    pluginId: String,
    throwable: Throwable,
) {
    logger.error(
        LogCategory.UI,
        "Render exception blamed on a plugin with no error boundary - quarantining it, window kept alive",
        mapOf(
            "pluginId" to pluginId,
            "errorType" to throwable.javaClass.simpleName,
        ),
        throwable,
    )
    CrashHandler.recordContained(throwable)
    if (pluginId.isNotBlank()) {
        PluginCrashRegistry.recordCrash(pluginId, throwable)
    }
}

/**
 * Keep the window, recover the plugin panels, and tell the user.
 *
 * Extracted from the handler rather than inlined: the block was long enough to
 * push `exceptionHandler` past the length limit, and the ordering here matters
 * enough to read on its own.
 */
private fun containRenderFault(
    throwable: Throwable,
    policy: RenderCrashPolicy,
) {
    logger.error(
        LogCategory.UI,
        "Unattributed render exception - contained, window kept alive",
        mapOf(
            "errorType" to throwable.javaClass.simpleName,
            "recentFailures" to policy.recentFailureCount().toString(),
        ),
        throwable,
    )
    CrashHandler.recordContained(throwable)
    val outcome = PluginRenderRecovery.onUnattributedRenderException(throwable)
    val madeProgress = noteRecoveryOutcome(policy, outcome)

    renderRecoveryToaster.toastFor(outcome, now = System.nanoTime() / 1_000_000)?.let { message ->
        StatusMessageManager.showMessage(message, durationMs = RENDER_RECOVERY_TOAST_MILLIS)
    }
    if (madeProgress) {
        Window.getWindows().forEach { it.repaint() }
    }
}

/**
 * Scope for fire-and-forget startup work (PSI warm-up, update-Realtime start).
 * Deliberately process-lifetime — main() has no teardown point; long-lived
 * services manage their own scopes and are disposed via the shutdown hook.
 * SupervisorJob so one failed warm-up doesn't cancel the others.
 */
private val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun main(args: Array<String>) {
    // -------------------------------------------------------------------------
    // Phase 1: Headless CLI & credential helper dispatch (before AWT / logging)
    // -------------------------------------------------------------------------
    when (val earlyResult = CliBootstrap.dispatchHeadless(args)) {
        is CliDispatchResult.Exit -> exitProcess(earlyResult.code)
        CliDispatchResult.Continue -> Unit
    }

    val startupBeganMs = System.currentTimeMillis()

    // -------------------------------------------------------------------------
    // Phase 2: Logging initialization
    // -------------------------------------------------------------------------
    BossLogger.configureFromEnvironment()
    BossLogger.initialize() // Register shutdown hook for log flushing

    // -------------------------------------------------------------------------
    // Phase 3: Platform setup, pre-AWT properties & settings warm-up
    // -------------------------------------------------------------------------
    PlatformSetup.applyMacAppearanceFromTheme()

    // Serve credential brokers to plugins
    ai.rever.boss.services.llm.BrokeredCredentialAccess.initialize(
        ai.rever.boss.llm.BrokeredCredentialProviderImpl,
    )

    // Plugin load remedy access resolver
    ai.rever.boss.components.plugin.PluginLoadRemedyAccess.initialize(
        ai.rever.boss.components.plugin.DesktopPluginLoadRemedyResolver,
    )

    // Warm settings singletons on IO thread
    startupScope.launch(Dispatchers.IO) {
        ai.rever.boss.components.workspaces.WorkspaceSettingsManager.currentSettings
        ai.rever.boss.focusmode.FocusModeSettingsManager.currentSettings
    }

    // Set WM_CLASS for Linux desktop integration (must be before any AWT init)
    PlatformSetup.setLinuxWMClass()

    // Set up proper temp directories for native libraries
    PlatformSetup.setupNativeLibraryPaths()

    // Publish browser configuration flags as system properties
    ChromiumFlagsSettingsManager.applyToSystemProperties()
    ai.rever.boss.config.SwipeNavSettingsManager.publish()
    ai.rever.boss.config.AutoPipSettingsManager.publish()

    // Compose UI rendering backend override (Skiko)
    ConfigLoader.getConfig("BOSS_SKIKO_RENDER_API")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { requested ->
            val known = ChromiumFlagKeys.SKIKO_RENDER_APIS
            val normalized = requested.uppercase()
            if (normalized in known) {
                System.setProperty("skiko.renderApi", normalized)
            } else {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Ignoring unrecognized BOSS_SKIKO_RENDER_API - letting Skiko auto-detect",
                    mapOf("value" to requested, "known" to known.joinToString("|")),
                )
            }
        }

    // Disable lightweight popups for HARDWARE_ACCELERATED rendering mode
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)

    // Uninstall protocol hook (Windows)
    when (val unregResult = CliBootstrap.handleProtocolUnregistration(args)) {
        is CliDispatchResult.Exit -> exitProcess(unregResult.code)
        CliDispatchResult.Continue -> Unit
    }

    // -------------------------------------------------------------------------
    // Phase 4: Crash handlers & single instance check
    // -------------------------------------------------------------------------
    CrashHandler.install()
    installCrashInterceptor()
    PluginExecutionBoundary.installPluginIdResolver(hostPluginIdResolver())

    PluginCrashRegistry.onCrashNotify = { pluginId, error ->
        val errorMsg =
            (error.message ?: error.javaClass.simpleName)
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .take(60)
        StatusMessageManager.showMessage(
            "Plugin '${displayPluginId(pluginId)}' crashed: $errorMsg",
            durationMs = 8000,
        )
    }

    logger.info(LogCategory.SYSTEM, "BOSS starting up")

    // Initialize microkernel infrastructure (no-op in MONOLITH mode)
    val kernelBootstrap: Any? =
        try {
            val bossMode =
                System.getenv("BOSS_MODE")
                    ?: ConfigLoader.getConfig("BOSS_MODE")
            if (bossMode == "KERNEL") {
                val cls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
                val modeClass = Class.forName("ai.rever.boss.process.ProcessMode")
                val kernelMode = modeClass.enumConstants.first { it.toString() == "KERNEL" }
                val instance = cls.getConstructor(modeClass).newInstance(kernelMode)
                cls.getMethod("initialize").invoke(instance)
                instance
            } else {
                null
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }

    // Single-instance check: ensure only one BOSS instance runs
    if (!SingleInstanceManager.acquireLock()) {
        logger.info(LogCategory.SYSTEM, "Another BOSS instance is already running")
        val forwarded = CliBootstrap.forwardToExistingInstance(args)
        exitProcess(if (forwarded) 0 else 1)
    }

    // -------------------------------------------------------------------------
    // Phase 5: Shutdown hook registration
    // -------------------------------------------------------------------------
    ShutdownSequence.register { kernelBootstrap }
    logger.info(LogCategory.SYSTEM, "Successfully acquired single-instance lock")

    // -------------------------------------------------------------------------
    // Phase 6: Overlays, window nets & Chromium engine preparation
    // -------------------------------------------------------------------------
    DefaultWindowIcon.install()

    startupScope.launch(Dispatchers.IO) {
        DefaultWorkingDirectory.ensureDefaultDirectory()
    }

    OverlaySetup.configure()

    val (chromiumNeedsDownload, engineLabel) = ChromiumBootstrap.prepare()

    // -------------------------------------------------------------------------
    // Phase 7: Post-lock CLI, keyboard interceptor, services & plugins
    // -------------------------------------------------------------------------
    CliBootstrap.dispatchPostLock(args)

    AWTKeyboardInterceptor.install()
    AppThemeSettingsManager.ensureInitialized()
    PasskeyPlatformInit.initialize()
    SettingsSearchIndex.registerWithGlobalSearch()
    PluginStoreSetup.initialize()

    startupScope.launch {
        AppUpdateRealtimeService.instance.apply {
            onReleaseChanged = {
                val updateCoordinator = UpdateCoordinator.instance
                updateCoordinator.checkForUpdatesInBackground()
                updateCoordinator.versionListManager.fetchVersions(forceRefresh = true)
            }
            start()
        }
    }

    ai.rever.boss.components.plugin.DefaultPlugin.Companion.loadPersistedPluginsInternal = { manager ->
        PluginStoreSetup.loadPersistedPlugins(manager)
    }

    GlobalLogCapture.start()
    ResourceModeConfig.publishToPlugins()

    if (ResourceModeConfig.mode.backgroundSamplingEnabled) {
        PerformanceMonitor.start()
    } else {
        logger.info(
            LogCategory.SYSTEM,
            "Performance sampling disabled by the resource mode",
            mapOf("mode" to ResourceModeConfig.mode.name),
        )
    }

    MemoryPressureWatchdog.start(startupScope)

    logger.debug(
        LogCategory.SYSTEM,
        "Environment info",
        mapOf(
            "cwd" to System.getProperty("user.dir"),
            "javaVersion" to System.getProperty("java.version"),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        ),
    )

    // Create initial window BEFORE application{} to prevent auto-recreation
    if (!chromiumNeedsDownload) {
        WindowManager.createNewWindow()
    }

    logger.info(
        LogCategory.SYSTEM,
        "Pre-UI startup complete",
        mapOf(
            "elapsedMs" to (System.currentTimeMillis() - startupBeganMs).toString(),
        ),
    )

    // -------------------------------------------------------------------------
    // Phase 8: Compose Application Entry & Window Loop
    // -------------------------------------------------------------------------
    application {
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val defaultExceptionHandlerFactory = LocalWindowExceptionHandlerFactory.current

        val renderCrashPolicy =
            remember {
                RenderCrashPolicy()
            }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val pluginAwareExceptionHandlerFactory =
            remember(defaultExceptionHandlerFactory) {
                object : WindowExceptionHandlerFactory {
                    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
                        val defaultHandler = defaultExceptionHandlerFactory.exceptionHandler(window)
                        return WindowExceptionHandler { throwable ->
                            val pluginId = PluginCrashInterceptor.attributeToPlugin(throwable)
                            val blamedPluginId =
                                if (pluginId != null || throwable.hasFatalCause()) {
                                    null
                                } else {
                                    PluginCrashInterceptor.blameFor(throwable)
                                }
                            when (decideWindowExceptionRoute(throwable, pluginId, renderCrashPolicy, blamedPluginId)) {
                                WindowExceptionRoute.PluginHandled -> {
                                    logger.warn(
                                        LogCategory.SYSTEM,
                                        "Compose exception intercepted for plugin",
                                        mapOf(
                                            "pluginId" to pluginId.orEmpty(),
                                            "errorType" to throwable.javaClass.simpleName,
                                        ),
                                    )
                                    PluginCrashInterceptor.tryHandle(pluginId.orEmpty(), throwable)
                                }

                                WindowExceptionRoute.QuarantinePlugin -> {
                                    quarantineBlamedPlugin(blamedPluginId.orEmpty(), throwable)
                                }

                                WindowExceptionRoute.Contain -> {
                                    containRenderFault(throwable, renderCrashPolicy)
                                }

                                WindowExceptionRoute.Escalate -> {
                                    logger.error(
                                        LogCategory.UI,
                                        "Render exception is not containable - escalating to the default handler",
                                        mapOf(
                                            "errorType" to throwable.javaClass.simpleName,
                                            "recentFailures" to renderCrashPolicy.recentFailureCount().toString(),
                                        ),
                                        throwable,
                                    )
                                    defaultHandler.onException(throwable)
                                }
                            }
                        }
                    }
                }
            }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides pluginAwareExceptionHandlerFactory,
        ) {
            var isDownloadingChromium by remember { mutableStateOf(chromiumNeedsDownload) }
            var downloadProgress by remember {
                mutableStateOf(ChromiumAutoDownloader.DownloadProgress(0, 0))
            }

            if (isDownloadingChromium) {
                val downloadWindowState =
                    rememberWindowState(
                        position = WindowPosition.Aligned(Alignment.Center),
                        width = 500.dp,
                        height = 220.dp,
                    )

                LaunchedEffect(downloadProgress.error != null) {
                    downloadWindowState.size =
                        DpSize(
                            500.dp,
                            if (downloadProgress.error != null) 360.dp else 220.dp,
                        )
                }

                Window(
                    onCloseRequest = { exitApplication() },
                    state = downloadWindowState,
                    title = "BOSS - Setup",
                    resizable = false,
                    icon = BossWindowIcon.painter,
                ) {
                    ApplyBossWindowIcon(window)

                    LaunchedEffect(Unit) {
                        ChromiumAutoDownloader.downloadChromium { progress ->
                            downloadProgress = progress
                            if (progress.isComplete) {
                                WindowManager.createNewWindow()
                                runCatching {
                                    ai.rever.boss.plugin.browser.FluckEngine.prewarmInBackground(force = true)
                                }
                                isDownloadingChromium = false
                            }
                        }
                    }

                    BossTheme {
                        Box(
                            modifier =
                                androidx.compose.ui.Modifier
                                    .fillMaxSize()
                                    .background(BossThemeController.current.colors.panel),
                        ) {
                            ChromiumDownloadContent(
                                progress = downloadProgress.progressFraction,
                                downloadedMB = downloadProgress.downloadedMB,
                                totalMB = downloadProgress.totalMB,
                                status =
                                    ai.rever.boss.components.dialogs.engineDownloadStatus(
                                        engineLabel = engineLabel,
                                        isExtracting = downloadProgress.isExtracting,
                                        totalBytes = downloadProgress.totalBytes,
                                    ),
                                error = downloadProgress.error,
                                onCancel = { exitApplication() },
                                onRetry = {
                                    downloadProgress = ChromiumAutoDownloader.DownloadProgress(0, 0)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        ChromiumAutoDownloader.downloadChromium { progress ->
                                            downloadProgress = progress
                                            if (progress.isComplete) {
                                                WindowManager.createNewWindow()
                                                runCatching {
                                                    ai.rever.boss.plugin.browser.FluckEngine.prewarmInBackground(force = true)
                                                }
                                                isDownloadingChromium = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (!isDownloadingChromium) {
                LaunchedEffect(Unit) {
                    CLICommandHandler.getInstance().initialize(
                        windowManager = WindowManager,
                        getSplitViewState = { null },
                    )
                }

                WindowManager.windows.forEach { windowState ->
                    key(windowState.id) {
                        BossWindow(
                            windowState = windowState,
                            onCloseRequest = {
                                val awtWindow = ai.rever.boss.utils.WindowFocusManager.getWindow(windowState.id)
                                var needsTransitionWait = false
                                if (awtWindow is java.awt.Frame) {
                                    if (awtWindow.extendedState != java.awt.Frame.NORMAL) {
                                        logger.debug(
                                            LogCategory.UI,
                                            "Exiting maximized state before window close",
                                            mapOf(
                                                "windowId" to windowState.id,
                                                "extendedState" to awtWindow.extendedState.toString(),
                                            ),
                                        )
                                        awtWindow.extendedState = java.awt.Frame.NORMAL
                                        needsTransitionWait = true
                                    }

                                    val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
                                    if (isMacOS) {
                                        val screenBounds =
                                            awtWindow.graphicsConfiguration
                                                ?.device
                                                ?.defaultConfiguration
                                                ?.bounds
                                        val windowBounds = awtWindow.bounds
                                        val isNativeFullscreen =
                                            screenBounds != null &&
                                                windowBounds.width >= screenBounds.width &&
                                                windowBounds.height >= screenBounds.height
                                        if (isNativeFullscreen) {
                                            try {
                                                logger.debug(
                                                    LogCategory.UI,
                                                    "Requesting macOS fullscreen exit before window close",
                                                    mapOf(
                                                        "windowId" to windowState.id,
                                                    ),
                                                )
                                                val appClass = Class.forName("com.apple.eawt.Application")
                                                val app = appClass.getMethod("getApplication").invoke(null)
                                                appClass
                                                    .getMethod("requestToggleFullScreen", java.awt.Window::class.java)
                                                    .invoke(app, awtWindow)
                                                needsTransitionWait = true
                                            } catch (e: Exception) {
                                                logger.debug(
                                                    LogCategory.UI,
                                                    "macOS fullscreen exit not available",
                                                    mapOf(
                                                        "errorType" to e.javaClass.simpleName,
                                                        "reason" to (e.message ?: "unknown"),
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    if (needsTransitionWait) {
                                        kotlinx.coroutines.runBlocking {
                                            kotlinx.coroutines.delay(150)
                                        }
                                    }
                                }

                                ai.rever.boss.components.window_panel.SplitViewStateRegistry
                                    .getState(windowState.id)
                                    ?.disposeAllBrowsersBlocking()

                                ai.rever.boss.run.RunnerTerminalService.cleanupWindow(windowState.id)
                                ai.rever.boss.services.terminal.TerminalAPIAccess.removeAllForWindow(windowState.id)

                                WindowManager.closeWindow(windowState.id)
                                ai.rever.boss.utils.WindowFocusManager.unregisterWindow(windowState.id)
                            },
                        )
                    }
                }
            }
        }
    }
}
