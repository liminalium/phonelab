import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<run number> so every build installs over the previous one.
val buildNumber = (findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "io.github.liminalium.phonelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.liminalium.phonelab"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // Prototype key, committed on purpose (see signing/README.md).
        create("prototype") {
            storeFile = rootProject.file("signing/prototype.p12")
            storeType = "pkcs12"
            storePassword = "prototype"
            keyAlias = "prototype"
            keyPassword = "prototype"
        }
    }

    buildTypes {
        release {
            // Shrinking takes the APK from ~20 MB to a few MB, which matters on slow phone downloads.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("prototype")
        }
        debug {
            signingConfig = signingConfigs.getByName("prototype")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
