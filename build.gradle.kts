import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

// Root build file: shared configuration for all JVM subprojects.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    `maven-publish`
}

allprojects {
    group = "dev.etdmnet"
    // Beta release: API surface is stable enough to consume but not yet 1.0.
    // Production users MUST also deploy a TURN server (see README).
    version = "0.4.0-beta"
}

subprojects {
    // Apply common JVM/Kotlin config only to JVM subprojects (not Android ones).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("passed", "failed", "skipped") }
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("etdmnet: ETDM-based peer-hosted multiplayer networking for Kotlin")
                    url.set("https://github.com/etdmnet/etdmnet")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("etdmnet")
                            name.set("etdmnet contributors")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/etdmnet/etdmnet.git")
                        developerConnection.set("scm:git:ssh://git@github.com:etdmnet/etdmnet.git")
                        url.set("https://github.com/etdmnet/etdmnet")
                    }
                }
            }

            // Generic private repo (Nexus / Artifactory / GitHub Packages).
            val repoUrl = providers.environmentVariable("MAVEN_REPO_URL").orNull
            val repoUser = providers.environmentVariable("MAVEN_REPO_USERNAME").orNull
            val repoPass = providers.environmentVariable("MAVEN_REPO_PASSWORD").orNull
            if (!repoUrl.isNullOrBlank() && !repoUser.isNullOrBlank() && !repoPass.isNullOrBlank()) {
                repositories {
                    maven {
                        name = "releaseRepo"
                        url = uri(repoUrl)
                        credentials {
                            username = repoUser
                            password = repoPass
                        }
                    }
                }
            }

            // Maven Central via Sonatype OSSRH (s01.oss.sonatype.org).
            // Required env: OSSRH_USERNAME, OSSRH_PASSWORD.
            // Repository is always registered so the publish task exists at
            // configure time. Missing credentials will simply fail at execute time.
            val ossrhUser = providers.environmentVariable("OSSRH_USERNAME").orNull
            val ossrhPass = providers.environmentVariable("OSSRH_PASSWORD").orNull
            repositories {
                maven {
                    name = "sonatype"
                    val isSnapshot = project.version.toString().endsWith("SNAPSHOT")
                    url = uri(
                        if (isSnapshot) "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                        else "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    )
                    if (!ossrhUser.isNullOrBlank() && !ossrhPass.isNullOrBlank()) {
                        credentials {
                            username = ossrhUser
                            password = ossrhPass
                        }
                    }
                }
            }
        }
    }

    // GPG signing for Maven Central artifacts. Activated only when signing
    // keys are present in the environment, so local builds stay friction-free.
    // Required env: SIGNING_KEY (ASCII-armored), SIGNING_PASSWORD.
    plugins.withId("maven-publish") {
        val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
        val signingPass = providers.environmentVariable("SIGNING_PASSWORD").orNull
        if (!signingKey.isNullOrBlank() && !signingPass.isNullOrBlank()) {
            apply(plugin = "signing")
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, signingPass)
                val pub = extensions.getByType<PublishingExtension>()
                sign(pub.publications)
            }
        }
    }
}
