plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is opt-in: only active when FZI_RELEASE_STORE_FILE is set
// via ~/.gradle/gradle.properties or ENV. Otherwise the release APK falls
// back to the default debug signing (good enough for local R8/minify tests).
val releaseSigningEnabled =
    (project.findProperty("FZI_RELEASE_STORE_FILE") as String?)?.isNotBlank() == true

android {
    namespace = "com.fzi.acousticscene"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.fzi.acousticscene"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(project.property("FZI_RELEASE_STORE_FILE") as String)
                storePassword = project.property("FZI_RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("FZI_RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("FZI_RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        // BuildConfig wird für die versionsgebundene Modell-Cache-Invalidierung
        // gebraucht (ModelInference); AGP 8+ generiert es nicht mehr per Default.
        buildConfig = true
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // PyTorch Mobile (Standard, nicht Lite - für Module.load())
    implementation("org.pytorch:pytorch_android:1.13.1")

    // ONNX Runtime für Speaker ID (SIQAS)
    implementation(libs.onnxruntime.android)

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // TarsosDSP für optimierte FFT (core reicht — genutzt wird nur util.fft.FFT)
    implementation("be.tarsos.dsp:core:2.5")

    // Gson für JSON Serialisierung (für PredictionRepository)
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Testing
    testImplementation(libs.junit)
    // ONNX Runtime JVM-Variante für Paritäts-Unit-Tests auf dem Host
    testImplementation(libs.onnxruntime.jvm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}