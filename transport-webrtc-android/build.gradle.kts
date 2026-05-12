// Opt-in Android transport. To build, set `etdmnet.includeAndroid=true` in
// `gradle.properties` or pass `-Petdmnet.includeAndroid=true` on the command line.
// This avoids forcing AGP on JVM-only contributors.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library") version "8.2.2"
    kotlin("android") version "1.9.25"
    `maven-publish`
}

android {
    namespace = "dev.etdmnet.transport.webrtc.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
    api(project(":signaling-ktor"))
    api(project(":turn-bundled"))
    api("io.github.webrtc-sdk:android:125.6422.06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "etdmnet-transport-webrtc-android"
            }
        }
    }
}
