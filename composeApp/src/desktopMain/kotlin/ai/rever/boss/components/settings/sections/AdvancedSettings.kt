package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.performance.PerformanceSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdvancedSettings() {
    val coroutineScope = rememberCoroutineScope()

    // Show the next-launch choice while retaining the running mode for the restart indicator.
    val modeOverride =
        remember {
            ai.rever.boss.config
                .bossModeOverrideSource()
        }
    val modeAvailable =
        remember {
            ai.rever.boss.config
                .kernelModeAvailable()
        }
    val modeRevision by ai.rever.boss.config.savedBossModeChanges
        .collectAsState()
    var kernelMode by remember { mutableStateOf(false) }
    var needsRestart by remember { mutableStateOf(false) }
    val initialMode =
        remember {
            ai.rever.boss.config.ConfigLoader
                .getConfig("BOSS_MODE") == "KERNEL"
        }
    var modeSaveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(modeRevision) {
        val mode = readBossMode()
        modeSaveError = null
        kernelMode = mode
        needsRestart = mode != initialMode
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Process Mode") {
            SettingsToggle(
                label = "Microkernel Mode",
                checked = kernelMode,
                onCheckedChange = { enabled ->
                    modeSaveError = null
                    coroutineScope.launch {
                        if (writeBossMode(enabled).isFailure) {
                            modeSaveError = "Could not save process mode. Check preference-file permissions and logs."
                        }
                    }
                },
                enabled = modeOverride == null && modeAvailable,
                description =
                    if (!modeAvailable) {
                        "Microkernel mode is unavailable in this build."
                    } else {
                        modeOverride?.let { "Controlled by $it" }
                            ?: "Run plugins in isolated processes with gRPC IPC and AI self-healing"
                    },
            )

            modeSaveError?.let { message ->
                Text(
                    text = message,
                    color = ai.rever.boss.plugin.ui.BossTheme.colors.alert,
                    fontSize = 11.sp,
                )
            }

            if (needsRestart) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = AccentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 0.dp,
                ) {
                    Text(
                        text = "Restart required for changes to take effect.",
                        fontSize = 11.sp,
                        color = AccentColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Always shown, including with Microkernel Mode off: this is where source egress is turned
        // on, and an operator should be able to read and set it before enabling the mode that runs
        // it. The readiness card says when the mode is what's holding it back.
        SelfHealingSettings(kernelMode = kernelMode && modeAvailable)

        // Plugin JVM Settings (only visible in KERNEL mode)
        if (kernelMode && modeAvailable) {
            val perfSettings by PerformanceSettingsManager.currentSettings.collectAsState()
            var pluginHeap by remember(perfSettings) { mutableStateOf(perfSettings.pluginJvmHeapMb.toFloat()) }
            var pluginInitHeap by remember(perfSettings) { mutableStateOf(perfSettings.pluginJvmInitialHeapMb.toFloat()) }

            SettingsSection(title = "Plugin JVM Resources") {
                SettingsSlider(
                    label = "Max Heap per Plugin",
                    value = pluginHeap,
                    onValueChange = { pluginHeap = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            PerformanceSettingsManager.updateSettings(
                                perfSettings.copy(pluginJvmHeapMb = pluginHeap.toInt()),
                            )
                        }
                    },
                    valueRange = 128f..8192f,
                    steps = 31,
                    valueDisplay = {
                        val mb = it.toInt()
                        if (mb >= 1024) "${"%.1f".format(mb / 1024f)} GB" else "$mb MB"
                    },
                    description = "Maximum heap size for each plugin child JVM. Requires plugin restart.",
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSlider(
                    label = "Initial Heap per Plugin",
                    value = pluginInitHeap,
                    onValueChange = { pluginInitHeap = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            PerformanceSettingsManager.updateSettings(
                                perfSettings.copy(pluginJvmInitialHeapMb = pluginInitHeap.toInt()),
                            )
                        }
                    },
                    valueRange = 32f..pluginHeap.coerceAtLeast(64f),
                    steps = 15,
                    valueDisplay = {
                        val mb = it.toInt()
                        if (mb >= 1024) "${"%.1f".format(mb / 1024f)} GB" else "$mb MB"
                    },
                    description = "Initial heap allocation. Higher values reduce GC during startup.",
                )
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = AccentColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 0.dp,
                ) {
                    val totalSystemMb =
                        remember {
                            ai.rever.boss.config.SystemMemory
                                .totalPhysicalBytes() / (1024 * 1024)
                        }
                    val totalPluginMb = pluginHeap.toLong() * 16
                    Text(
                        text =
                            "System RAM: ${"%.1f".format(totalSystemMb / 1024f)} GB  •  " +
                                "Max plugin allocation: ${"%.1f".format(totalPluginMb / 1024f)} GB (16 plugins × ${pluginHeap.toInt()} MB)",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Info card
        SettingsSection(title = "About") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Microkernel Architecture",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "When enabled, plugins and services run in isolated child processes " +
                                "with gRPC-based IPC, and a crashed process is diagnosed and recovered " +
                                "by the orchestrator rather than simply restarted. Asking a model to " +
                                "propose a source patch is a separate opt-in, configured above. " +
                                "When disabled, everything runs in a single JVM process (default).",
                        fontSize = 11.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

private suspend fun readBossMode(): Boolean =
    withContext(Dispatchers.IO) {
        ai.rever.boss.config.ConfigLoader
            .bossModeForNextLaunch() == "KERNEL"
    }

private suspend fun writeBossMode(enabled: Boolean) =
    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        ai.rever.boss.config
            .saveBossMode(enabled)
    }
