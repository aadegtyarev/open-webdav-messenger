import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.compose.compiler)
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
        versionCode = 42
        versionName = "0.17.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ui-chat-surface feature: the Compose UI build feature. The Compose compiler is supplied by the
    // org.jetbrains.kotlin.plugin.compose plugin (Kotlin 2.0.x), so no composeOptions block is needed.
    buildFeatures {
        compose = true
    }

    // The checked-in Room schema JSON is part of the androidTest assets so MigrationTestHelper can
    // open older schema versions on-device (Room migration test, stack-notes Room migrations).
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    // Release signing: reads keystore from environment variables set by CI.
    // KEYSTORE_BASE64 = base64-encoded .keystore file
    // KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD = self-explanatory
    // On local builds, also reads from signing.properties (gitignored).
    // Falls back to debug signing when neither is available.
    val localKeystoreFile = File(projectDir, "release.keystore")
    val localPropFile = File(projectDir, "signing.properties")
    val localProps =
        if (localPropFile.exists()) {
            val p = Properties()
            localPropFile.inputStream().use { p.load(it) }
            p
        } else {
            null
        }
    val releaseSigningConfig =
        if (System.getenv("KEYSTORE_BASE64") != null || localKeystoreFile.exists()) {
            signingConfigs.create("release") {
                if (System.getenv("KEYSTORE_BASE64") != null) {
                    localKeystoreFile.writeBytes(Base64.getDecoder().decode(System.getenv("KEYSTORE_BASE64")))
                }
                storeFile = localKeystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps?.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: localProps?.getProperty("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: localProps?.getProperty("KEY_PASSWORD")
            }
        } else {
            null
        }

    buildTypes {
        debug {
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
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
            all {
                // The Compose createComposeRule tests launch the empty ComponentActivity host that
                // compose-ui-test-manifest contributes via debugImplementation (so the host stays out of
                // the release APK). That activity is only in the debug merged manifest, so the
                // screen-level UI tests run in the debug unit-test variant only; release unit tests would
                // fail to resolve the host. Skip them in testReleaseUnitTest — the debug run is canonical.
                if (it.name == "testReleaseUnitTest") {
                    it.exclude("**/ui/onboarding/CreateCommunityScreenTest*")
                    it.exclude("**/ui/onboarding/JoinScreenTest*")
                    it.exclude("**/ui/invite/InviteScreenTest*")
                    it.exclude("**/ui/feed/ChatFeedScreenTest*")
                    it.exclude("**/ui/feed/ChatFeedAutoScrollTest*")
                    it.exclude("**/ui/AppRootTest*")
                }
            }
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

    // ui-chat-surface feature: Jetpack Compose (BOM-aligned), the Activity-Compose host, and the
    // lifecycle ViewModel-Compose binding (per-screen ViewModels, state hoisted — arch note Choice 4).
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    // ui-tooling is debug-only (Compose @Preview rendering); never shipped in release.
    debugImplementation(libs.compose.ui.tooling)

    // QR: pure-Java generation (no native .so) + camera scan (no Play Services, no native .so).
    // Scan view is wrapped in a Compose AndroidView; the paste path is the mandated fallback
    // (stack-notes QR-generate / QR-scan; arch note Choice 5).
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // local-history-encryption: encrypts the local Room DB at rest via SQLCipher.
    implementation(libs.sqlcipher)

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
    // Compose UI tests via createComposeRule run under Robolectric on the JVM (stack-notes Compose
    // testing). The test manifest supplies the empty ComponentActivity host the rule launches; it must
    // be a debugImplementation so its <activity> declaration merges into the debug variant manifest
    // Robolectric reads (a testImplementation manifest is not merged, so the rule can't resolve the host).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // QR decode (MultiFormatReader over a BinaryBitmap) is JVM-testable with zxing core; the scan
    // wrapper's live camera path is a manual on-device step (stack-notes QR-scan).
    testImplementation(libs.zxing.core)

    // Instrumented (connectedAndroidTest): native .so ABI load + Android Keystore wrap/unwrap.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Room MigrationTestHelper for the on-device schema-migration test (stack-notes Room migrations).
    androidTestImplementation(libs.room.testing)
}
