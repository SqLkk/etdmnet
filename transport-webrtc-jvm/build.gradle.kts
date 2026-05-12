plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    api(project(":core"))
    api(project(":signaling-ktor"))
    api(project(":turn-bundled"))

    // Cross-platform WebRTC binding for the JVM (works on Win/macOS/Linux).
    // Native libraries are bundled inside the JAR and extracted at runtime.
    api("dev.onvoid.webrtc:webrtc-java:0.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "etdmnet-transport-webrtc-jvm"
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            pom.withXml {
                val deps = asNode().appendNode("dependencies")
                fun addDep(group: String, artifact: String, version: String, scope: String = "compile") {
                    val d = deps.appendNode("dependency")
                    d.appendNode("groupId", group)
                    d.appendNode("artifactId", artifact)
                    d.appendNode("version", version)
                    d.appendNode("scope", scope)
                }

                val v = project.version.toString()
                addDep(project.group.toString(), "etdmnet-core", v)
                addDep(project.group.toString(), "etdmnet-signaling-ktor", v)
                addDep(project.group.toString(), "etdmnet-turn-bundled", v)
                addDep("dev.onvoid.webrtc", "webrtc-java", "0.9.0")
                addDep("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.8.1", "runtime")
            }
        }
    }
}
