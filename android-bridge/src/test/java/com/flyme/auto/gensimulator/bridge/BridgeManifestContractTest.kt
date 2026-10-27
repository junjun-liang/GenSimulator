package com.flyme.auto.gensimulator.bridge

import com.flyme.auto.gencards.entry.GenCardsEntryContract
import com.flyme.auto.gencards.voice.contract.GenCardsVoiceIngressContract
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeManifestContractTest {
    @Test
    fun bridgeDeclaresOnlyCreateIngressGenCardsPermission() {
        val manifest = sequenceOf(
            File("android-bridge/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
        val permissionNames = document.getElementsByTagName("uses-permission")
            .let { nodes ->
                (0 until nodes.length)
                    .mapNotNull { index ->
                        nodes.item(index).attributes.getNamedItem("android:name")?.nodeValue
                    }
                    .toSet()
            }

        assertTrue(GenCardsVoiceIngressContract.PERMISSION_SUBMIT_QUERY in permissionNames)
        // CREATE uses only the Binder ingress; external entry Activities are not part of this path.
        assertFalse(GenCardsEntryContract.PERMISSION_OPEN_EXTERNAL_ENTRY in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissionNames)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissionNames)
    }

    @Test
    fun bridgeDeclaresForegroundServiceAndVisibleBootstrapActivity() {
        val manifest = sequenceOf(
            File("android-bridge/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val service = (0 until document.getElementsByTagName("service").length)
            .map { document.getElementsByTagName("service").item(it) }
            .first { it.attributes.getNamedItem("android:name")?.nodeValue == ".BridgeRuntimeService" }
        assertTrue(service.attributes.getNamedItem("android:foregroundServiceType")?.nodeValue == "specialUse")

        val activity = (0 until document.getElementsByTagName("activity").length)
            .map { document.getElementsByTagName("activity").item(it) }
            .first { it.attributes.getNamedItem("android:name")?.nodeValue == ".BridgeBootstrapActivity" }
        assertTrue(activity.attributes.getNamedItem("android:exported")?.nodeValue == "true")
    }

    @Test
    fun bridgeSourcesNeverLaunchGenCardsActivities() {
        val sourceRoot = sequenceOf(
            File("android-bridge/src/main/java"),
            File("src/main/java"),
        ).first { it.isDirectory }
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                "startActivity(" in text || "GenCardsEntryContract" in text
            }
            .map { it.name }
            .toList()

        assertTrue("GenCards opens its own Generation window: $offenders", offenders.isEmpty())
    }
}
