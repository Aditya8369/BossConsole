package ai.rever.boss.config

import java.util.Properties

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
    } + if (enabled) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"

@Synchronized
internal fun writeSavedBossMode(enabled: Boolean) {
    val file =
        ai.rever.boss.plugin.pathutils.BossDirectories
            .resolve("env_vars")
    file.parentFile?.mkdirs()
    val lines = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()
    file.writeText(withSavedBossMode(lines, enabled).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
}
