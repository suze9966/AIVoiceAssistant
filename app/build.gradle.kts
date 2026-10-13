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

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = java.util.Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("xiaomo") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
