plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// Portable JDK selection: Gradle auto-detects an installed JDK 17 (or provisions one via the
// foojay-resolver-convention plugin in settings.gradle.kts) instead of a hardcoded java.home.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.openwebdav.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openwebdav.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources {
            // JNA + lazysodium each ship duplicate license/notice META-INF entries; the merge
            // task fails on the collision. Pick the first — these are non-code legal text files.
            excludes +=
                setOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/LICENSE.md",
                    "META-INF/LICENSE-notice.md",
                )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Crypto substrate: lazysodium-android + JNA, both as @aar so the per-ABI native .so
    // (libsodium.so / libjnidispatch.so) is packaged into the APK (stack-notes Crypto: a
    // missing ABI = UnsatisfiedLinkError — caught by connectedAndroidTest on device 5c3ff0).
    // lazysodium-android transitively bumps androidx.core (1.16.0); we exclude it so the feature
    // keeps the version the rest of the build pins. We also exclude its transitive plain `jna`
    // jar and declare jna explicitly as @aar — otherwise BOTH the jna jar and jna aar land in the
    // APK and DEX merge fails on duplicate com.sun.jna classes.
    implementation(libs.lazysodium.android) {
        artifact { type = "aar" }
        exclude(group = "androidx.core", module = "core-ktx")
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna.aar) {
        artifact { type = "aar" }
    }

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM crypto: lazysodium-java loads the host's system libsodium so AEAD + Argon2id run
    // in `./gradlew test` (the host has libsodium.so.23 installed). JNA plain jar (not @aar).
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna.jar)

    // Instrumented (connectedAndroidTest): native .so ABI load + Android Keystore wrap/unwrap.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
