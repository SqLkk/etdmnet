plugins {
    kotlin("jvm") version "1.9.25"
    `maven-publish`
}

group = "dev.etdmnet"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// JitPack reads this publication automatically.
publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = "dev.etdmnet"
            artifactId = "etdmnet"
            version = "0.1.0"
        }
    }
}
