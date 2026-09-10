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
        assertEquals(null, parseEnvVars(disabled).getProperty("BOSS_MODE"))
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
            assertEquals(null, parseEnvVars(file.readLines()).getProperty("BOSS_MODE"))
            writeSavedBossMode(true, file)
            assertEquals("KERNEL", parseEnvVars(file.readLines()).getProperty("BOSS_MODE"))
            assertEquals(listOf("env_vars"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
