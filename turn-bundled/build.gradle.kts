plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    // Pure-Kotlin helpers — no transport dependency so this module is safe to
    // use from any consumer (JVM transport, Android transport, or even a
    // future iOS bridge).
    api(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "etdmnet-turn-bundled"
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
            }
        }
    }
}
