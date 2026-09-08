plugins {
    id("com.android.application")
}

android {
    namespace = "pt.xsoulp.domotica"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.xsoulp.domotica"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
