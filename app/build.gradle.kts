plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tabgestures"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tabgestures"
        minSdk = 30
        targetSdk = 34
        // CI passes these from the git tag (see .github/workflows/release.yml).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        // Same key as tools/build-apk.sh, so every build can update an existing install.
        create("shared") {
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    testImplementation("junit:junit:4.13.2")
}
