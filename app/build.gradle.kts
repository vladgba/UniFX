import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.fileman"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.fileman"
        minSdk = 21
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Drop Kotlin's runtime null-check intrinsics: smaller dex, no behavior change for our code.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

// No dependencies: Android framework + Kotlin stdlib only.
