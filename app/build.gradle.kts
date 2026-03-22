plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketscope"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketscope"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Networking (TCP Server)
    implementation("io.ktor:ktor-network:3.4.1")

    // Async / Concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // FITS Image Generation (raw Bayer to FITS conversion)
    implementation("gov.nasa.gsfc.heasarc:nom-tam-fits:1.21.2")

    // XML Serialization (for INDI protocol XML generation)
    implementation("io.github.pdvrieze.xmlutil:core:0.90.3")

    // XML Pull Parser (for JVM unit tests — Android has this built-in)
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Lifecycle for Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
