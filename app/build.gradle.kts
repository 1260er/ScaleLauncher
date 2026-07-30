plugins { id("com.android.application") }

android {
    namespace = "de.pritcloud.scalelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pritcloud.scalelauncher"
        minSdk = 31
        targetSdk = 35
        versionCode = 22
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
