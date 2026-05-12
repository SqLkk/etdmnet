rootProject.name = "etdmnet"

include(":core")
include(":signaling-ktor")
include(":signaling-server")
include(":turn-bundled")
include(":transport-webrtc-jvm")
include(":samples:jvm-chat")

// Android modules are opt-in: they require Android Gradle Plugin which we do
// not want to force on contributors who only build the JVM artifacts.
val includeAndroid = providers.gradleProperty("etdmnet.includeAndroid").orNull == "true"
if (includeAndroid) {
    include(":transport-webrtc-android")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
