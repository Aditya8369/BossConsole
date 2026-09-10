package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvVarsTest {
    @Test
    fun `menu disable comments stop enabling kernel mode`() {
        assertEquals("KERNEL", parseEnvVars(listOf("BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
        assertEquals(null, parseEnvVars(listOf("# BOSS_MODE=KERNEL")).getProperty("BOSS_MODE"))
    }

    @Test
    fun `parser ignores malformed and blank entries and preserves embedded equals`() {
        val values = parseEnvVars(listOf("bad", "=bad", "BOSS_MODE= ", " A = first ", "A=value=tail"))
        assertEquals(1, values.size)
        assertEquals("value=tail", values.getProperty("A"))
    }

    @Test
    fun `saved mode uses the last active assignment`() {
        val values = parseEnvVars(listOf("BOSS_MODE=KERNEL", "  # BOSS_MODE=KERNEL", "BOSS_MODE=MONOLITH"))
        assertEquals("MONOLITH", values.getProperty("BOSS_MODE"))
    }

    @Test
    fun `writer round trips duplicate and tab indented keys without damaging other keys`() {
        val lines = listOf("BOSS_MODE=KERNEL", "\tBOSS_MODE = KERNEL", "BOSS_MODE_EXTRA=keep", "# BOSS_MODE=KERNEL")
        val disabled = withSavedBossMode(lines, false)
        assertEquals("MONOLITH", parseEnvVars(disabled).getProperty("BOSS_MODE"))
        assertEquals("keep", parseEnvVars(disabled).getProperty("BOSS_MODE_EXTRA"))
        val enabled = withSavedBossMode(disabled, true)
        assertEquals("KERNEL", parseEnvVars(enabled).getProperty("BOSS_MODE"))
        assertEquals(1, enabled.count { it == "BOSS_MODE=KERNEL" })
    }

    @Test
    fun `atomic writer preserves unrelated preferences and leaves no partial`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("saved-mode")
                .toFile()
        try {
            val file = java.io.File(dir, "env_vars")
            file.writeText("BOSS_MODE=KERNEL\nOTHER=keep\n")
            writeSavedBossMode(false, file)
            assertEquals("keep", parseEnvVars(file.readLines()).getProperty("OTHER"))
            assertEquals("MONOLITH", parseEnvVars(file.readLines()).getProperty("BOSS_MODE"))
            writeSavedBossMode(true, file)
            assertEquals("KERNEL", parseEnvVars(file.readLines()).getProperty("BOSS_MODE"))
            assertEquals(listOf("env_vars"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saved mode rereads writes without changing runtime snapshot and preserves permissions`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("mode-reread")
                .toFile()
        try {
            val file = java.io.File(dir, "env_vars")
            file.writeText("BOSS_MODE=MONOLITH\n")
            val runtime = ConfigLoader.getConfig("BOSS_MODE")
            val posix =
                java.nio.file.Files.getFileAttributeView(
                    file.toPath(),
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                )
            val privateMode =
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------")
            posix?.setPermissions(privateMode)
            writeSavedBossMode(true, file)
            assertEquals("KERNEL", ConfigLoader.bossModeForNextLaunch(file, null, null))
            assertEquals(runtime, ConfigLoader.getConfig("BOSS_MODE"))
            writeSavedBossMode(false, file)
            assertEquals("MONOLITH", ConfigLoader.bossModeForNextLaunch(file, null, null))
            assertEquals(runtime, ConfigLoader.getConfig("BOSS_MODE"))
            if (posix != null) assertEquals(privateMode, posix.readAttributes().permissions())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `save failure is a result and preserves the original destination`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("mode-write-failure")
                .toFile()
        try {
            kotlin.test.assertTrue(saveBossMode(true, dir).isFailure)
            kotlin.test.assertTrue(dir.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `successful saves notify other settings surfaces and failures do not`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("mode-observers")
                .toFile()
        try {
            val before = savedBossModeChanges.value
            kotlin.test.assertTrue(saveBossMode(true, java.io.File(dir, "env_vars")).isSuccess)
            assertEquals(before + 1, savedBossModeChanges.value)
            kotlin.test.assertTrue(saveBossMode(false, dir).isFailure)
            assertEquals(before + 1, savedBossModeChanges.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `build without kernel classes is unavailable`() {
        kotlin.test.assertFalse(kernelModeAvailable(object : ClassLoader(null) {}))
    }
}
