plugins { id("com.android.application") }

android {
    namespace = "de.pritcloud.scalelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pritcloud.scalelauncher"
        minSdk = 31
        targetSdk = 35
        versionCode = 26
        versionName = "2.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}
