plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sidephone.atvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidephone.atvremote"
        // SidePhone SP-01 ships AOSP Android 12 (API 31). minSdk 26 keeps the door
        // open for other keypad Androids while still allowing modern APIs.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // All Apple TV pairing/session crypto (Ed25519, X25519, ChaCha20-Poly1305,
    // SHA-512, HKDF, SRP big-integer math) runs on BouncyCastle's lightweight API
    // so it behaves identically across Android API levels (no GMS/JCA surprises).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Local JVM unit tests for the pairing/session crypto. The expected values are
    // reference vectors cross-checked against pyatv, srptools and `cryptography`.
    testImplementation("junit:junit:4.13.2")
}
