// ──────────────────────────────────────────────────────────────────────
// libtd/build.gradle.kts — vendored TDLib JNI bindings
//
// Vendored from upstream TDLib (td/libtdjson via tdjni.so) — see
// libtd/src/main/java for TdApi.java / Client.java / NativeClient.java.
//
// We vendor (instead of pulling from Maven) because:
//   - The Maven artifact `org.drinkless:tdlib` ships libtdjson.so but
//     its .so is often stale; the libtdjni.so in our mirror is
//     versioned per release and matches TDLib 1.8.30 schema.
//   - TV-only build doesn't need to depend on upstream's release cadence.
//
// License: Boost Software License 1.0 (TDLib). See libtd/src/main/.
// ──────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.library")
}

android {
    namespace = "tv.telegram.libtd"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            jniLibs.srcDirs("src/main/libs")
        }
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // @Nullable / @IntDef annotations used throughout TdApi.java
    implementation("androidx.annotation:annotation:1.8.2")
}