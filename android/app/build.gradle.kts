import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.pyvideotrans.subtitle"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pyvideotrans.subtitle"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"

        buildConfigField("String", "WHISPER_CPP_VERSION", "\"v1.9.5\"")
        buildConfigField("String", "FFMPEG_KIT_VERSION", "\"8.1.9\"")
        buildConfigField("String", "SHERPA_ONNX_COMMIT", "\"935e5ded3d94\"")
        buildConfigField("String", "QNN_VERSION", "\"2.40.0.251030\"")
        buildConfigField("String", "ONNXRUNTIME_VERSION", "\"1.28.2\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWHISPER_DIR=${rootProject.projectDir}/third_party/whisper.cpp",
                    "-DVulkan_INCLUDE_DIR=${rootProject.projectDir}/third_party/Vulkan-Headers/include",
                    "-DGGML_VULKAN=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DWHISPER_ALL_WARNINGS=OFF",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = false
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The sherpa-onnx Kotlin API is kept in sync with the pinned native
            // commit instead of being copied into this repository.
            java.srcDir(rootProject.projectDir.resolve("third_party/sherpa-onnx/sherpa-onnx/kotlin-api"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // QNN loads its DSP skeleton libraries from nativeLibraryDir and
            // ADSP_LIBRARY_PATH, so the native libraries must be extracted.
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ffmpegkit.full.gpl)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
