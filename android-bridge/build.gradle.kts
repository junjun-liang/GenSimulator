plugins {
    id("com.android.application")
}

android {
    namespace = "com.flyme.auto.gensimulator.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flyme.auto.gensimulator.bridge"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val trustedKeystore = providers.environmentVariable("GENCARDS_TRUSTED_KEYSTORE").orNull
    val trustedAlias = providers.environmentVariable("GENCARDS_TRUSTED_KEY_ALIAS").orNull
    val trustedStorePassword = providers.environmentVariable("GENCARDS_TRUSTED_STORE_PASSWORD").orNull
    val trustedKeyPassword = providers.environmentVariable("GENCARDS_TRUSTED_KEY_PASSWORD").orNull
    val hasTrustedSigning = listOf(
        trustedKeystore,
        trustedAlias,
        trustedStorePassword,
        trustedKeyPassword,
    ).all { !it.isNullOrBlank() }

    if (hasTrustedSigning) {
        signingConfigs {
            create("trusted") {
                storeFile = file(trustedKeystore!!)
                storePassword = trustedStorePassword
                keyAlias = trustedAlias
                keyPassword = trustedKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasTrustedSigning) {
                signingConfig = signingConfigs.getByName("trusted")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared-voice-ingress-contract"))
    testImplementation("junit:junit:4.13.2")
}
