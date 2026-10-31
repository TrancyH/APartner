plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amoolink.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amoolink.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // 零外部依赖:TLS/Keystore/JSON(org.json)/通知/ContentProvider 均为框架能力
}
