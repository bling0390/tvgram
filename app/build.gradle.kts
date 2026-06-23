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

    defaultConfig {
        applicationId = "tv.telegram"
        minSdk        = 21
        targetSdk     = 34
        versionCode   = 11
        versionName   = "1.0.0"

        // Telegram API credentials come from local.properties (gitignored)
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val tgApiId   = localProps.getProperty("TG_API_ID",   "")
        val tgApiHash = localProps.getProperty("TG_API_HASH", "")

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
