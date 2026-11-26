// AirTouch Android 客户端 —— app 模块
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airtouch.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airtouch.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.0.3"
    }

    signingConfigs {
        // 命令行/沙箱构建若无权写 ~/.android/debug.keystore，可用此环境变量指向一份固定的
        // debug 密钥库；未设置时完全保持 AGP 默认行为，Android Studio 常规构建不受影响。
        getByName("debug") {
            System.getenv("AIRTOUCH_DEBUG_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // 不开启混淆压缩，避免在无编译环境时引入额外风险；规则文件仍然提供。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        // 旧版 View/XML 界面已整体删除，只保留 Jetpack Compose 版控制台。
        compose = true
    }

    // Kotlin 1.9.24 对应 Compose Compiler 1.5.14（未使用 Kotlin 2.0 的 compose 插件）。
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- 第一步：Jetpack Compose（仅用于 ui/redesign/ 静态重构页与 @Preview）----
    // 显式锁定版本，避免 BOM 带来的额外解析；全部与 Compose Compiler 1.5.14 兼容。
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
    implementation("androidx.compose.foundation:foundation:1.6.7")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.7")

    // 说明：PinnedTrust 直接使用平台自带的 java.net.ssl / javax.net.ssl，
    // 不引入 okhttp-tls，以减少依赖面（okhttp-tls 需要额外确认 OkHttp 版本兼容性）。
}
