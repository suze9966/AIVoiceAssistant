import java.util.Properties
import java.io.File
import java.util.Base64

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
        // ---- 版本号自动递增 ----
        // GitHub Actions 会给每个 step 自动注入 GITHUB_RUN_NUMBER，每次构建 +1，
        // 所以直接读它就能让 versionCode 单调递增，覆盖安装永远不会被版本号拦住。
        // 这样不必改 .github/workflows/build.yml，也就不需要 workflow 权限。
        //
        // 取值优先级：
        //   1. XIAOMO_VERSION_CODE / XIAOMO_VERSION_NAME（显式覆盖，本地或特殊构建用）
        //   2. GITHUB_RUN_NUMBER + 1000（CI 自动递增）
        //   3. 本地固定值 2 / "1.1"
        //
        // 为什么加 1000 偏移：run_number 从个位数起步，
        // 而设备上历史版本已经是 versionCode=2，直接用 run_number 可能反而变小，
        // 加上偏移后永远大于历史版本。
        val versionCodeBase = 1000
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull()
        val autoVersionCode = ciRunNumber?.let { versionCodeBase + it }
        val autoVersionName = ciRunNumber?.let { "1.1.$it" }
        val explicitVersionCode = System.getenv("XIAOMO_VERSION_CODE")?.trim()?.toIntOrNull()
        val explicitVersionName = System.getenv("XIAOMO_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }

        versionCode = explicitVersionCode ?: autoVersionCode ?: 2
        versionName = explicitVersionName ?: autoVersionName ?: "1.1"
        project.logger.lifecycle(
            "VERSION_CODE=$versionCode VERSION_NAME=$versionName " +
                "(run=${ciRunNumber ?: "local"}, base=$versionCodeBase)"
        )
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

    val envKeystoreB64 = System.getenv("XIAOMO_KEYSTORE_B64")

    // 仓库内固定签名材料对应的口令（与 app/signing/xiaomo-fixed.jks.b64 配套）。
    val repoStorePassword = "xiaomo2026"
    val repoKeyAlias = "xiaomo"
    val repoKeyPassword = "xiaomo2026"

    val resolvedStorePassword = System.getenv("XIAOMO_STORE_PASS")
        ?: fileProps.getProperty("storePassword")
        ?: repoStorePassword
    val resolvedKeyAlias = System.getenv("XIAOMO_KEY_ALIAS")
        ?: fileProps.getProperty("keyAlias")
        ?: repoKeyAlias
    val resolvedKeyPassword = System.getenv("XIAOMO_KEY_PASS")
        ?: fileProps.getProperty("keyPassword")
        ?: repoKeyPassword

    var resolvedStorePath: String? = fileProps.getProperty("storeFile")

    // 仓库内固定签名材料（app/signing/xiaomo-fixed.jks.b64）。
    // CI 环境没有本地 keystore.properties，也没有注入 Secret 时用它，
    // 保证每次构建签名一致，可直接覆盖安装。
    val repoB64File = File(project.projectDir, "signing/xiaomo-fixed.jks.b64")
    if (envKeystoreB64.isNullOrBlank() && resolvedStorePath.isNullOrBlank() && repoB64File.exists()) {
        val outDir = File(rootProject.projectDir, "build/ci-signing")
        outDir.mkdirs()
        val jks = File(outDir, "xiaomo-fixed.jks")
        val b64 = repoB64File.readText().filter { !it.isWhitespace() }
        jks.writeBytes(Base64.getDecoder().decode(b64))
        resolvedStorePath = jks.absolutePath
        logger.lifecycle("SIGNING_SOURCE=repo-b64")
    }

    if (!envKeystoreB64.isNullOrBlank()) {
        val outDir = File(rootProject.projectDir, "build/ci-signing")
        outDir.mkdirs()
        val jks = File(outDir, "xiaomo-debug.jks")
        if (!jks.exists() || jks.length() == 0L) {
            jks.writeBytes(Base64.getDecoder().decode(envKeystoreB64.trim()))
        }
        resolvedStorePath = jks.absolutePath
        logger.lifecycle("SIGNING_SOURCE=env")
    } else if (resolvedStorePath == null) {
        logger.lifecycle("SIGNING_SOURCE=default debug keystore")
    }

    val signingReady = !resolvedStorePath.isNullOrBlank() &&
        !resolvedStorePassword.isNullOrBlank() &&
        !resolvedKeyAlias.isNullOrBlank() &&
        !resolvedKeyPassword.isNullOrBlank()

    signingConfigs {
        if (signingReady) {
            create("xiaomo") {
                storeFile = file(resolvedStorePath!!)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
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
