// LBA2-DS: the dual-screen build for the Retroid Pocket Duo Lite.
//
// The engine is built by scripts/dev/build-android.sh (CMake + NDK) as before;
// scripts/dev/build-android-ds.sh then stages liblba2cc.so as libmain.so, with
// libSDL3.so and libc++_shared.so, into src/main/jniLibs, and runs this build.
// What Gradle adds is the Kotlin side: the activity that keeps the game on the
// top screen and the Compose panel on the bottom one.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repoRoot = rootProject.projectDir.parentFile

// The LBA2-DS release version: the one place to bump per release. The APK's
// name, the release tag (vX.Y.Z) and the version shown on the bottom screen
// all come from it; the engine keeps its own in /VERSION.
val releaseVersion = "1.0.0"

// major * 10000 + minor * 100 + patch, so each release has a higher code.
val derivedVersionCode = run {
    val p = releaseVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val (major, minor, patch) = Triple(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }, p.getOrElse(2) { 0 })
    require(minor in 0..99 && patch in 0..99) { "releaseVersion $releaseVersion: minor and patch must be 0..99" }
    major * 10000 + minor * 100 + patch
}
val sdlJava = repoRoot.resolve("out/android/SDL3/android-project/app/src/main/java")

android {
    namespace = "org.lbalab.lba2cc"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Its own id, so it installs beside the upstream org.lbalab.lba2cc build.
        applicationId = "io.github.sergeshack.lba2ds"
        minSdk = 26
        //noinspection OldTargetApi: same storage behaviour as the upstream APK
        targetSdk = 34
        versionCode = derivedVersionCode
        versionName = releaseVersion
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(sdlJava, repoRoot.resolve("packaging/android/java"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the build machine's debug key until a release key exists.
            signingConfig = signingConfigs.getByName("debug")
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

    // Stored uncompressed and page-aligned, so the loader maps them straight
    // from the APK (16 KB pages on Android 15+).
    packaging { jniLibs { useLegacyPackaging = false } }

    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
