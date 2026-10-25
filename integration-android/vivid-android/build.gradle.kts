// vivid-android: Android library that hosts the MOSIP Vivid liveness engine on-device.
// Compiles with AGP 8.3.2 / Kotlin 1.9.24 (see ../../docs/testing/android-compile.md); not yet run on a device.
import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.mosip.vivid.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // Registration client itself is minSdk 28; 24 keeps the library reusable.
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    compileOptions {
        // liveness-api / device-api are compiled for Java 17 (records are not used, but
        // java.util.Optional, java.time and default interface methods are) -> desugaring on.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        // Models are memory-mapped by TFLite / MediaPipe; they must stay uncompressed in the APK.
        noCompress += listOf("tflite", "task")
    }

    testOptions {
        // android.jar stubs return defaults instead of throwing in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // MediaPipe and TFLite both ship libtensorflowlite_jni; keep the first copy.
        jniLibs.pickFirsts += listOf("**/libtensorflowlite_jni.so", "**/libtensorflowlite_gpu_jni.so")
    }
}

dependencies {
    // Frozen contracts from the mosip-vivid monorepo (plain Java 17 jars).
    // Publish them with `mvn -pl liveness-api,device-api install` and consume from mavenLocal(),
    // or drop the jars into vivid-android/libs/.
    api("io.mosip.vivid:liveness-api:0.1.0-SNAPSHOT")
    api("io.mosip.vivid:device-api:0.1.0-SNAPSHOT")
    implementation("io.mosip.vivid:liveness-core:0.1.0-SNAPSHOT")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.annotation:annotation:1.8.0")

    // Flutter embedding (BinaryMessenger, TextureRegistry, used by the bridge and the Pigeon output).
    // compileOnly: inside ARC the Flutter Gradle plugin puts the real engine jar on the app classpath.
    compileOnly("io.flutter:flutter_embedding_debug:1.0.0-${flutterEngineVersion()}")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for PolicyJson tests (android.jar only has stubs).
    testImplementation("org.json:json:20231013")
}

/**
 * Engine revision for the compileOnly Flutter embedding: the `vivid.flutterEngineVersion` Gradle property,
 * else `<flutter.sdk>/bin/internal/engine.version` from the root project's local.properties (which Flutter
 * writes into every app's android/ directory, ARC included).
 */
fun flutterEngineVersion(): String {
    (findProperty("vivid.flutterEngineVersion") as String?)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val lp = rootProject.file("local.properties")
    val sdk = if (lp.exists()) Properties().apply { lp.inputStream().use { load(it) } }.getProperty("flutter.sdk") else null
    val f = sdk?.let { File(it, "bin/internal/engine.version") }
    require(f != null && f.exists()) { "Set vivid.flutterEngineVersion or flutter.sdk in local.properties" }
    return f.readText().trim()
}
