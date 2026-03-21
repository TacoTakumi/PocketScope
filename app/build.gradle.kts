plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketscope"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketscope"
        minSdk = 29
        targetSdk = 34
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
}

dependencies {
    // Networking (TCP Server)
    implementation("io.ktor:ktor-network:3.4.1")

    // Async / Concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // XML Serialization (for INDI protocol XML generation)
    implementation("io.github.pdvrieze.xmlutil:core:0.90.3")

    // XML Pull Parser (for JVM unit tests — Android has this built-in)
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
