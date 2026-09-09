package ai.rever.boss.config

import java.util.Properties

/** Parse the simple KEY=value format written by the process-mode menu. */
internal fun parseEnvVars(lines: List<String>): Properties =
    Properties().apply {
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    setProperty(parts[0].trim(), parts[1].trim())
                }
            }
        }
    }
