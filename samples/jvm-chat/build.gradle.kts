plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.etdmnet.samples.chat.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport-webrtc-jvm"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
