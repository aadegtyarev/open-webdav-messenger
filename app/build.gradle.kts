plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.kapt)
}

// Portable JDK selection: Gradle auto-detects an installed JDK 17 (or provisions one via the
// foojay-resolver-convention plugin in settings.gradle.kts) instead of a hardcoded java.home.
kotlin {
    jvmToolchain(17)
}

// Room schema export (stack-notes Room: exportSchema=true + checked-in JSON so migrations are
// reviewable and the Room MigrationTestHelper can validate them under connectedAndroidTest). The
// JSON lives in app/schemas/ and is committed to VCS. Declared at the top level so KAPT applies it
// to the Room annotation processor (not nested in defaultConfig, which logs an unrecognized-arg warning).
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

android {
    namespace = "org.openwebdav.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openwebdav.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The checked-in Room schema JSON is part of the androidTest assets so MigrationTestHelper can
    // open older schema versions on-device (Room migration test, stack-notes Room migrations).
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources/manifest available to JVM unit tests
            // so it can supply a Context for Room in-memory DBs and the WorkManager TestDriver.
            isIncludeAndroidResources = true
        }
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

    // sync feature: Room (local message history + per-chat sync cursor) and WorkManager
    // (periodic background poll). Room DAOs are suspend/Flow only (stack-notes Room: no
    // main-thread access); schema is exported to app/schemas/ for reviewable migrations.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    implementation(libs.paging.runtime)
    kapt(libs.room.compiler)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // WorkManager TestDriver — drives the periodic Worker under `./gradlew test`
    // (stack-notes WorkManager: work-testing integration test).
    testImplementation(libs.work.testing)
    // Room in-memory DB for DAO/Flow tests on the JVM (stack-notes Room: in-memory test DB).
    testImplementation(libs.room.runtime)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.runner)
    // Robolectric supplies the host SQLite binding + Context so Room/WorkManager run on the JVM.
    testImplementation(libs.robolectric)
    // JVM crypto: lazysodium-java loads the host's system libsodium so AEAD + Argon2id run
    // in `./gradlew test` (the host has libsodium.so.23 installed). JNA plain jar (not @aar).
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna.jar)

    // Instrumented (connectedAndroidTest): native .so ABI load + Android Keystore wrap/unwrap.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Room MigrationTestHelper for the on-device schema-migration test (stack-notes Room migrations).
    androidTestImplementation(libs.room.testing)
}
