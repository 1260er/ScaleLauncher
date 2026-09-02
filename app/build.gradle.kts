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

val requireReleaseSigning =
    providers.environmentVariable("SCALELAUNCHER_REQUIRE_RELEASE_SIGNING")
        .orNull
        ?.equals("true", ignoreCase = true)
        ?: false

android {
    namespace = "de.pritcloud.scalelauncher"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pritcloud.scalelauncher"
        minSdk = 31
        targetSdk = 35
        versionCode = providers.environmentVariable("SCALELAUNCHER_VERSION_CODE").orNull?.toIntOrNull() ?: 7
        versionName = "1.5.1"
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
    if (requireReleaseSigning) {
        dependsOn("verifyReleaseSigningEnvironment")
    }
}

dependencies {
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
