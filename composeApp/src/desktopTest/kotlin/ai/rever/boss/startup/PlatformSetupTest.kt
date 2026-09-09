package ai.rever.boss.startup

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PlatformSetupTest {
    @Test
    fun extractPty4jNativesCreatesTargetPlatformDirectory() {
        val tempDir = createTempDirectory("pty4j_test").toFile()
        try {
            PlatformSetup.extractPty4jNatives(
                targetDir = tempDir,
                osName = "linux",
                osArch = "x86_64",
                classLoader = object : ClassLoader(null) {},
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
                classLoader = object : ClassLoader(null) {},
            )
            val expectedDir = File(tempDir, "darwin")
            assertTrue(expectedDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun extractionUsesFallbackResourceAndPreservesExistingNative() {
        val tempDir = createTempDirectory("pty4j_fixture").toFile()
        val requested = mutableListOf<String>()
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): java.io.InputStream? {
                requested.add(name)
                return if (name == "native/linux/x86-64/libpty.so") {
                    ByteArrayInputStream("fixture-native".toByteArray())
                } else null
            }
        }
        try {
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            val native = File(tempDir, "linux/x86-64/libpty.so")
            assertEquals("fixture-native", native.readText())
            assertEquals(4, requested.size)
            requested.clear()
            PlatformSetup.extractPty4jNatives(tempDir, "linux", "amd64", loader)
            assertTrue(requested.isEmpty())
            assertEquals("fixture-native", native.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

}
