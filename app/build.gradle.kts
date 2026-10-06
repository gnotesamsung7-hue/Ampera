// "java" inside this script means the Java plugin extension, so import URI explicitly.
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.eyebot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ampera"
        minSdk = 26          // Android 8.0+ (adaptive icons, modern CameraX)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // ---------------------------------------------------------------------
    // Release signing. CI passes these as environment variables (from GitHub
    // secrets). If they are absent, the release APK is built unsigned.
    // ---------------------------------------------------------------------
    val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
    }
    // TFLite models are memory-mapped straight from the APK, so they must stay uncompressed.
    androidResources {
        noCompress += "tflite"
    }
}

// ---------------------------------------------------------------------------------------------
// v4: download MediaPipe's EfficientDet-Lite0 animal/object model into assets at build time
// (about 4.4 MB, Apache-2.0). If the download fails the app still builds; animal spotting is
// just switched off.
// ---------------------------------------------------------------------------------------------
val animalModel = file("src/main/assets/efficientdet_lite0.tflite")
val downloadAnimalModel by tasks.registering {
    // No declared outputs on purpose: the file lands in src/, and Gradle would otherwise insist
    // every task reading sources depends on this one. It is a no-op once the file exists.
    doLast {
        if (animalModel.exists() && animalModel.length() > 100_000) return@doLast
        val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
        try {
            animalModel.parentFile.mkdirs()
            val tmp = File(animalModel.path + ".part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() > 100_000) tmp.renameTo(animalModel) else tmp.delete()
            logger.lifecycle("EyeBot: downloaded animal model (${animalModel.length() / 1024} KB)")
        } catch (e: Exception) {
            logger.warn("EyeBot: could not download the animal model ($url): ${e.message}. Animal spotting will be disabled.")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadAnimalModel) }

dependencies {
    val cameraxVersion = "1.4.1"
    val lifecycleVersion = "2.8.7"

    // AndroidX core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Jetpack Lifecycle (lifecycleScope, repeatOnLifecycle)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // CameraX
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Google ML Kit face detection (bundled model: works offline, no Play Services download)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // v3: ML Kit barcode scanning for QR cards (bundled model, offline)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // v3: USB OTG serial to the pan-tilt microcontroller (CP210x, CH340, FTDI, CDC-ACM)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    // v4: on-device face recognition (MobileFaceNet in assets) and animal detection
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Unit tests for the pure-Kotlin parts (QR parser, colour classifier, servo maths, synth)
    testImplementation("junit:junit:4.13.2")
}
