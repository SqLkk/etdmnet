plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

dependencies {
    api(project(":core"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("io.ktor:ktor-client-core:2.3.12")
    api("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "etdmnet-signaling-ktor"
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
                addDep("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.6.3")
                addDep("io.ktor", "ktor-client-core", "2.3.12")
                addDep("io.ktor", "ktor-client-websockets", "2.3.12")
                addDep("io.ktor", "ktor-client-okhttp", "2.3.12", "runtime")
                addDep("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.8.1", "runtime")
            }
        }
    }
}
