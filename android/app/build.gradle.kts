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
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    // 协议核心保持框架能力:TLS/Keystore/JSON(org.json)/通知/ContentProvider
    // MTA(互传联盟)传输层:照搬 CatShare 的 ktor 选型(MIT,实测与小米/OPPO/vivo 原生互通)
    val ktor = "3.0.3"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    implementation("io.ktor:ktor-server-core:$ktor")
    // 强制较新的 Netty:ktor 3.0.3 默认带的 4.1.116 在 Android 上会把客户端的 ALPN
    // 选择误判为不支持,直接抛 AssertionError,导致一加/OPPO 的 WSS 握手失败。
    implementation(platform("io.netty:netty-bom:4.1.118.Final"))
    implementation("io.ktor:ktor-server-netty:$ktor")
    // 注:曾试过 ktor-server-cio(不做 ALPN),但 CIO 引擎不支持服务端 HTTPS,不可用。
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor")
}
