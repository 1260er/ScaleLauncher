plugins { id("com.android.application") }

val releaseStoreFile = providers.environmentVariable("SCALELAUNCHER_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("SCALELAUNCHER_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SCALELAUNCHER_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SCALELAUNCHER_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "de.pritcloud.scalelauncher"
    compileSdk = 35

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "de.pritcloud.scalelauncher"
        minSdk = 31
        targetSdk = 35
        versionCode = providers.environmentVariable("SCALELAUNCHER_VERSION_CODE").orNull?.toIntOrNull() ?: 3
        versionName = "1.2.1"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.register("verifyReleaseSigningEnvironment") {
    doLast {
        check(releaseSigningConfigured) {
            "Release-Signierung fehlt. Erforderlich sind SCALELAUNCHER_KEYSTORE_PATH, " +
                "SCALELAUNCHER_KEYSTORE_PASSWORD, SCALELAUNCHER_KEY_ALIAS und " +
                "SCALELAUNCHER_KEY_PASSWORD."
        }
        val store = file(requireNotNull(releaseStoreFile))
        check(store.isFile) { "Keystore nicht gefunden: ${store.absolutePath}" }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("verifyReleaseSigningEnvironment")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.gms:play-services-nearby:19.4.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}
