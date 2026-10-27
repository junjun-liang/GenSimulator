import org.gradle.api.GradleException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

plugins {
    id("com.android.library")
}

android {
    namespace = "com.flyme.auto.gencards.voice.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

private fun normalizedAidl(file: java.io.File): String = file
    .readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("//") }
    .joinToString(" ")

val verifyVoiceIngressAidlAbi by tasks.registering {
    group = "verification"
    description = "Verifies the stable GenCards shared Binder ABIs and method order."
    val voiceIngress = "src/main/aidl/com/flyme/auto/gencards/voice/aidl/IGenCardsVoiceQueryIngress.aidl"
    val editCallback = "src/main/aidl/com/flyme/auto/gencards/voice/aidl/IGenCardsEditQueryCallback.aidl"
    val editCapture = "src/main/aidl/com/flyme/auto/gencards/voice/aidl/IGenAgentEditQueryCapture.aidl"
    val aidlFiles = linkedMapOf(
        voiceIngress to layout.projectDirectory.file(voiceIngress),
        editCallback to layout.projectDirectory.file(editCallback),
        editCapture to layout.projectDirectory.file(editCapture),
    )
    inputs.files(aidlFiles.values)
    doLast {
        val expected = linkedMapOf(
            voiceIngress to (
                "package com.flyme.auto.gencards.voice.aidl; " +
                    "interface IGenCardsVoiceQueryIngress { " +
                    "void submitVoiceQuery(String query, int sceneType); " +
                    "boolean submitTextCreateQuery(String query); " +
                    "}"
                ),
            editCallback to (
                "package com.flyme.auto.gencards.voice.aidl; " +
                    "interface IGenCardsEditQueryCallback { " +
                    "void onQueryCaptured(String requestId, String query); " +
                    "void onQueryCancelled(String requestId); " +
                    "void onQueryFailed(String requestId, String errorCode, boolean retryable); " +
                    "}"
                ),
            editCapture to (
                "package com.flyme.auto.gencards.voice.aidl; " +
                    "import com.flyme.auto.gencards.voice.aidl.IGenCardsEditQueryCallback; " +
                    "interface IGenAgentEditQueryCapture { " +
                    "boolean requestEditQuery(String requestId, IGenCardsEditQueryCallback callback); " +
                    "void cancelEditQuery(String requestId); " +
                    "}"
                ),
        )
        expected.forEach { (path, abi) ->
            val normalized = normalizedAidl(aidlFiles.getValue(path).asFile)
            if (normalized != abi) {
                throw GradleException("AIDL ABI drifted for $path. Expected: '$abi' Actual: '$normalized'")
            }
        }
    }
}

private fun contractFingerprint(files: List<java.io.File>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }.forEach { file ->
        val relativePath = file.relativeTo(projectDir).invariantSeparatorsPath
        digest.update(relativePath.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        val normalized = file.readText().replace("\r\n", "\n").replace("\r", "\n")
        digest.update(normalized.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifyVoiceIngressContractFingerprint by tasks.registering {
    group = "verification"
    description = "Rejects local shared-contract source drift unless the checked-in fingerprint is updated."
    val contractSources = fileTree("src/main") {
        include("aidl/**/*.aidl")
        include("java/**/*.java")
    }
    val fingerprintFile = layout.projectDirectory.file("contract-fingerprint.sha256")
    inputs.files(contractSources)
    inputs.file(fingerprintFile)
    doLast {
        val expected = fingerprintFile.asFile.readText().trim()
        val actual = contractFingerprint(contractSources.files.toList())
        if (expected != actual) {
            throw GradleException(
                "Shared Voice Ingress contract fingerprint drifted. " +
                    "Expected $expected but was $actual. Mirror the same contract sources from GenCards " +
                    "before intentionally refreshing contract-fingerprint.sha256."
            )
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyVoiceIngressAidlAbi)
    dependsOn(verifyVoiceIngressContractFingerprint)
}
