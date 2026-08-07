// ──────────────────────────────────────────────────────────────────────
// app/build.gradle.kts — Tvgram Android TV app
// See docs/ARCHITECTURE.md, docs/BUILD.md, docs/RELEASE.md
// ──────────────────────────────────────────────────────────────────────
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load signing config from gitignored keystore.properties
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace  = "tv.telegram"
    compileSdk = 35

    // Load local.properties once (gitignored; never commit). Both
    // defaultConfig and buildTypes need these values, so we lift the
    // load out of defaultConfig into android-block scope.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val tgApiId   = localProps.getProperty("TG_API_ID",   "")
    val tgApiHash = localProps.getProperty("TG_API_HASH", "")

    // Proxy config — applied ONLY in buildTypes.debug below; release
    // gets empty defaults so TdClient.enableProxy() no-ops. Keeps
    // developer proxy infrastructure out of distributed release APKs.
    // Empty host or 0 port in debug also makes enableProxy() a no-op,
    // so leaving these unset is fine for devs outside firewall regions.
    val proxyHost = localProps.getProperty("PROXY_HOST", "")
    val proxyPort = localProps.getProperty("PROXY_PORT", "")
    val proxyType = localProps.getProperty("PROXY_TYPE", "socks5")
    val proxyUser = localProps.getProperty("PROXY_USER", "")
    val proxyPass = localProps.getProperty("PROXY_PASS", "")

    // Auto-incrementing build number. Each successful assembleDebug
    // bumps BUILD_NUMBER in local.properties via the doLast block at
    // the bottom of this file. versionCode + versionName both use it,
    // so APK filenames stay unique across rebuilds without manual edits.
    // Default 1 if BUILD_NUMBER is missing from local.properties.
    val buildNumber = (localProps.getProperty("BUILD_NUMBER", "1").toIntOrNull() ?: 1)

    defaultConfig {
        applicationId = "tv.telegram"
        minSdk        = 21
        targetSdk     = 34
        versionCode   = buildNumber
        versionName   = "1.0.0.$buildNumber"

        // Telegram API credentials — applied to both debug AND release
        // (same Telegram app, same credentials regardless of build type).
        buildConfigField("int",    "TG_API_ID",   tgApiId)
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Multi-ABI splits (D-005)
    splits {
        abi {
            isEnable        = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk  = true
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                keyAlias      = keystoreProperties["keyAlias"]      as String?
                keyPassword   = keystoreProperties["keyPassword"]   as String?
                storeFile     = (keystoreProperties["storeFile"]    as String?)
                    ?.let { rootProject.file(it) }
                storePassword = keystoreProperties["storePassword"] as String?
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Proxy ONLY in debug builds. PROXY_HOST comes from
            // local.properties; from inside an Android emulator the host
            // machine's loopback is 10.0.2.2 (NOT 127.0.0.1 — that's the
            // emulator itself). If host is empty or port is 0,
            // TdClient.enableProxy() no-ops, so leaving these unset is
            // fine for devs outside firewall regions (China, Iran, etc.).
            buildConfigField("String", "PROXY_HOST", "\"$proxyHost\"")
            buildConfigField("int",    "PROXY_PORT", if (proxyPort.isNotBlank()) proxyPort else "0")
            buildConfigField("String", "PROXY_TYPE", "\"$proxyType\"")
            buildConfigField("String", "PROXY_USER", "\"$proxyUser\"")
            buildConfigField("String", "PROXY_PASS", "\"$proxyPass\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }

            // No proxy in release. TdClient.enableProxy() short-circuits
            // on empty host / 0 port, so this is effectively a no-op call.
            // Explicit BuildConfig fields (vs. omitting) make it obvious
            // to anyone reading the source that release builds are
            // proxy-free.
            buildConfigField("String", "PROXY_HOST", "\"\"")
            buildConfigField("int",    "PROXY_PORT", "0")
            buildConfigField("String", "PROXY_TYPE", "\"\"")
            buildConfigField("String", "PROXY_USER", "\"\"")
            buildConfigField("String", "PROXY_PASS", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Custom APK filenames are applied in scripts/publish-apks.sh (rename
    // pass after copy) rather than via androidComponents.onVariants —
    // AGP 8.4's new variants API exposes VariantOutput as a read-only
    // metadata object with no `filename` setter, and the legacy
    // applicationVariants API has type-resolution issues in Kotlin DSL
    // (.all { } is inferred as the stdlib Boolean predicate). The shell
    // approach is simpler, fully version-controlled via build.gradle.kts
    // reads, and survives AGP upgrades.
    //
    // Final names: tvgram-<version>-<buildType>-<abi|universal>.apk
    //   - debug builds:   tvgram-1.0.0-debug-arm64-v8a.apk
    //   - release builds: tvgram-1.0.0-release-arm64-v8a.apk
    //   - universal:      tvgram-1.0.0-<buildType>-universal.apk

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Auto-bump BUILD_NUMBER in local.properties after each successful
    // assembleDebug. Next build will then be versionCode = buildNumber+1
    // and APK filename tvgram-1.0.0.<N+1>-debug-<abi>.apk.
    afterEvaluate {
        // ?.doLast works without the type-inference gotcha of ?.configure { }
        // (configure has multiple overloads; Kotlin can't pick one from a
        // lambda body that doesn't reference the receiver).
        tasks.findByName("assembleDebug")?.doLast {
            val newBuildNumber = buildNumber + 1
            val propsFile = rootProject.file("local.properties")
            val updatedProps = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
                setProperty("BUILD_NUMBER", newBuildNumber.toString())
            }
            propsFile.outputStream().use {
                updatedProps.store(it, null)
            }
            logger.lifecycle("🔢 Bumped BUILD_NUMBER to $newBuildNumber (next APK will be 1.0.0.$newBuildNumber-debug-*.apk)")
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose (BOM-aligned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    // Compose for TV (D-006, D-013)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Java 8+ API desugaring (required for media3 1.7 on minSdk 21-25)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ExoPlayer for video playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.ui.compose)

    // Coil for image loading
    implementation(libs.coil.compose)

    // TDLib (D-002, D-026, D-027, D-029) — JNI bindings via libtd module
    //   libtdjni.so + TdApi.java vendored under :libtd
    //   Loaded at runtime by Client.create() via System.loadLibrary("tdjni")
    implementation(project(":libtd"))

    // QR rendering (D-003)
    implementation(libs.zxing.core)

    // Unit / JVM tests
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests (won't run on vultr — they're for completeness)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
