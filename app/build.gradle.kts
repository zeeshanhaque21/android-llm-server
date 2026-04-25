import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Resolve a keystore for release builds.
//
// Priority:
//   1. ANDROID_KEYSTORE_PATH  (explicit — use a real release key in CI)
//   2. ~/.android/debug.keystore  (auto-created by any Gradle Android
//      build; installable on-device but NOT meant for store distribution)
//
// The auto-fallback is so `make release` produces an installable APK on
// a fresh checkout without manual keystore setup. Anyone shipping past
// "send it to my own phone" should set the env vars to point at a
// proper keystore.
val envKeystore: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val debugKeystore: String = "${System.getProperty("user.home")}/.android/debug.keystore"
val resolvedKeystore: String? = when {
    envKeystore != null && File(envKeystore).exists() -> envKeystore
    File(debugKeystore).exists() -> debugKeystore
    else -> null
}

android {
    namespace = "com.zeeshan.androidllmserver"
    compileSdk = 35

    // Name release/debug APKs after the project so GitHub release downloads
    // show "android-llm-server-v0.2.0.apk" instead of the default
    // "app-release.apk".
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "android-llm-server-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    defaultConfig {
        applicationId = "com.zeeshan.androidllmserver"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // arm64 only. We don't use emulators (see CLAUDE.md testing
            // strategy), and dropping x86_64 roughly halves native build
            // time since llama.cpp is compiled per-ABI.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    // Parallelize the underlying ninja build across all cores.
                    "-DCMAKE_BUILD_PARALLEL_LEVEL=24"
                )
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (resolvedKeystore != null) {
                storeFile     = file(resolvedKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
                keyAlias      = System.getenv("ANDROID_KEY_ALIAS")         ?: "androiddebugkey"
                keyPassword   = System.getenv("ANDROID_KEY_PASSWORD")      ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only wire the signing config when we actually have a keystore;
            // otherwise the output is "-unsigned" and the Makefile warns.
            if (resolvedKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{INDEX.LIST,io.netty.versions.properties}"
        }
        jniLibs {
            // libOpenCL.so is a build-time-only stub from
            // third_party/opencl-stub — used solely so the linker can
            // resolve clCreateContext et al. when building
            // libggml-opencl.so. The Android dynamic linker, given the
            // <uses-native-library> manifest entry, supplies the real
            // driver from /vendor/lib64/libOpenCL.so at load time.
            // Shipping our stub would shadow the real driver and turn
            // every OpenCL call into a no-op.
            excludes += "**/libOpenCL.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
}
