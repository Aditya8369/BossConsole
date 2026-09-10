package ai.rever.boss.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Properties

private val modeRevision = MutableStateFlow(0L)
internal val savedBossModeChanges = modeRevision.asStateFlow()

/** Parse the simple KEY=value format written by the process-mode menu. */
internal fun parseEnvVars(lines: List<String>): Properties {
    val properties = Properties()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            properties.setProperty(parts[0].trim(), parts[1].trim())
        }
    }
    return properties
}

/** Replace all exact mode assignments without modifying similarly named keys. */
internal fun withSavedBossMode(
    lines: List<String>,
    enabled: Boolean,
): List<String> =
    lines.filterNot { line ->
        line
            .trim()
            .removePrefix("#")
            .trim()
            .substringBefore('=')
            .trim() == "BOSS_MODE"
    } + if (enabled) "BOSS_MODE=KERNEL" else "BOSS_MODE=MONOLITH"

@Synchronized
internal fun writeSavedBossMode(
    enabled: Boolean,
    file: java.io.File =
        ai.rever.boss.plugin.pathutils.BossDirectories
            .resolve("env_vars"),
) {
    file.parentFile?.mkdirs()
    val lines = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()
    val partial =
        java.nio.file.Files
            .createTempFile(file.absoluteFile.parentFile.toPath(), "env_vars-", ".part")
            .toFile()
    try {
        partial.writeText(withSavedBossMode(lines, enabled).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        if (file.exists()) {
            val view =
                java.nio.file.Files.getFileAttributeView(
                    file.toPath(),
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                )
            view?.readAttributes()?.permissions()?.let {
                java.nio.file.Files
                    .setPosixFilePermissions(partial.toPath(), it)
            }
        }
        java.nio.file.Files.move(
            partial.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        modeRevision.update { it + 1 }
    } finally {
        partial.delete()
    }
}

internal fun bossModeOverrideSource(): String? =
    when {
        !System.getenv("BOSS_MODE").isNullOrBlank() -> "BOSS_MODE environment variable"
        !System.getProperty("BOSS_MODE").isNullOrBlank() -> "BOSS_MODE system property"
        else -> null
    }

/** Expected filesystem failures must be reported without cancelling a settings composition. */
internal fun saveBossMode(
    enabled: Boolean,
    file: java.io.File =
        ai.rever.boss.plugin.pathutils.BossDirectories
            .resolve("env_vars"),
): Result<Unit> {
    val result =
        try {
            writeSavedBossMode(enabled, file)
            Result.success(Unit)
        } catch (error: java.io.IOException) {
            Result.failure(error)
        } catch (error: SecurityException) {
            Result.failure(error)
        }
    return result.onFailure { error ->
        ai.rever.boss.utils.logging.BossLogger.forComponent("ProcessModeSettings").warn(
            ai.rever.boss.utils.logging.LogCategory.SYSTEM,
            "Could not save process mode",
            error = error,
        )
    }
}

internal fun kernelModeAvailable(classLoader: ClassLoader = ConfigLoader::class.java.classLoader): Boolean =
    try {
        Class.forName("ai.rever.boss.kernel.KernelBootstrap", false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
