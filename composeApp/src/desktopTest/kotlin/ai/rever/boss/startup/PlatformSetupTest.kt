package ai.rever.boss.startup

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformSetupTest {
    @Test
    fun extractPty4jNativesCreatesTargetPlatformDirectory() {
        val tempDir = createTempDirectory("pty4j_test").toFile()
        try {
            PlatformSetup.extractPty4jNatives(
                targetDir = tempDir,
                osName = "linux",
                osArch = "x86_64",
            )
            val expectedDir = File(tempDir, "linux/x86-64")
            assertTrue(expectedDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractPty4jNativesHandlesMacPlatform() {
        val tempDir = createTempDirectory("pty4j_test_mac").toFile()
        try {
            PlatformSetup.extractPty4jNatives(
                targetDir = tempDir,
                osName = "mac os x",
                osArch = "aarch64",
            )
            val expectedDir = File(tempDir, "darwin")
            assertTrue(expectedDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
