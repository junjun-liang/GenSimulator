package com.flyme.auto.gensimulator.adb

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdbLocatorTest {
    @Test
    fun manualPathWins() {
        val root = createTempDirectory("adb-locator")
        val adb = Files.createFile(root.resolve("adb.exe"))
        val locator = DefaultAdbLocator(
            manualPath = adb,
            env = emptyMap(),
            osName = "Windows 11",
            userHome = root.toString(),
        )

        assertEquals(adb, locator.locate())
    }

    @Test
    fun androidHomeIsResolved() {
        val root = createTempDirectory("adb-sdk")
        val platformTools = root.resolve("platform-tools").createDirectories()
        val adb = Files.createFile(platformTools.resolve("adb.exe"))
        val locator = DefaultAdbLocator(
            env = mapOf("ANDROID_HOME" to root.toString()),
            osName = "Windows 11",
            userHome = root.toString(),
        )

        assertEquals(adb, locator.locate())
    }

    @Test
    fun androidSdkRootIsResolved() {
        val root = createTempDirectory("adb-sdk-root")
        val platformTools = root.resolve("platform-tools").createDirectories()
        val adb = Files.createFile(platformTools.resolve("adb.exe"))
        val locator = DefaultAdbLocator(
            env = mapOf("ANDROID_SDK_ROOT" to root.toString()),
            osName = "Windows 11",
            userHome = root.toString(),
        )

        assertEquals(adb, locator.locate())
    }

    @Test
    fun windowsPlatformFallbackIsResolved() {
        val localAppData = createTempDirectory("adb-localappdata")
        val platformTools = localAppData.resolve("Android").resolve("Sdk").resolve("platform-tools").createDirectories()
        val adb = Files.createFile(platformTools.resolve("adb.exe"))
        val locator = DefaultAdbLocator(
            env = mapOf("LOCALAPPDATA" to localAppData.toString()),
            osName = "Windows 11",
            userHome = localAppData.toString(),
        )

        assertEquals(adb, locator.locate())
    }

    @Test
    fun returnsNullWhenNoCandidateExists() {
        val root = createTempDirectory("adb-none")
        val locator = DefaultAdbLocator(
            env = emptyMap(),
            osName = "Windows 11",
            userHome = root.toString(),
        )

        assertNull(locator.locate())
    }
}
