import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.suze.aivoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suze.aivoice"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // ---- 固定签名材料 ----
    // 优先读环境变量里的 keystore(base64)，CI 用 GitHub Secrets 注入；
    // 其次读本地 keystore.properties；都没有则回退 Gradle 默认 debug keystore。
    // 这样保证每次 CI 产出的 APK 签名一致，可以直接覆盖安装旧版本。
    val propsFile = rootProject.file("keystore.properties")
    val fileProps = Properties()
    if (propsFile.exists()) {
        propsFile.inputStream().use { fileProps.load(it) }
    }

    val b64 = System.getenv("XIAOMO_KEYSTORE_B64")
    val storePass = System.getenv("XIAOMO_STORE_PASS") ?: fileProps.getProperty("storePassword")
    val keyAlias = System.getenv("XIAOMO_KEY_ALIAS") ?: fileProps.getProperty("keyAlias")
    val keyPass = System.getenv("XIAOMO_KEY_PASS") ?: fileProps.getProperty("keyPassword")

    var signingStorePath: String? = fileProps.getProperty("storeFile")
    if (!b64.isNullOrBlank()) {
        val out = java.io.File(rootProject.buildDir, "ci-signing")
        out.mkdirs()
        val jks = java.io.File(out, "xiaomo-debug.jks")
        if (!jks.exists() || jks.length() == 0L) {
            jks.writeBytes(java.util.Base64.getDecoder().decode(b64))
        }
        signingStorePath = jks.absolutePath
    }

    val signingReady = !signingStorePath.isNullOrBlank() &&
        !storePass.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() &&
        !keyPass.isNullOrBlank()

    signingConfigs {
        if (signingReady) {
            create("xiaomo") {
                storeFile = file(signingStorePath!!)
                storePassword = storePass
                keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.findByName("xiaomo") != null) {
                signingConfig = signingConfigs.getByName("xiaomo")
            }
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("xiaomo") != null) {
                signingConfig = signingConfigs.getByName("xiaomo")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        // 内置的 1000 张表情图不压缩，assets.open() 直接读取，更快也不占额外解压
        noCompress.add("png")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
