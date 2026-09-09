package ai.rever.boss.kernel

/** One status slot must retain every failure, even when other services start successfully. */
internal fun serviceStartupSummary(
    spawned: Int,
    missing: List<String>,
    failed: List<String>,
): String =
    buildList {
        if (missing.isNotEmpty()) {
            add("Missing JARs: ${missing.joinToString(", ")}. Check the installation and service logs.")
        }
        if (failed.isNotEmpty()) add("Failed to spawn: ${failed.joinToString(", ")}. Check service logs.")
        add("Microkernel: $spawned service(s) spawned (readiness not verified).")
    }.joinToString(" ")
