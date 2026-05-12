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

            // Maven Central via Sonatype OSSRH.
            // NOTE: The legacy s01.oss.sonatype.org endpoint was sunset on
            // 2025-06-30 and now returns HTTP 402. Maven Central publishing
            // requires the new Central Portal (https://central.sonatype.com),
            // which uses a different upload API — migration is tracked
            // separately (will land via the jreleaser plugin).
            //
            // For now the "sonatype" repository points at the new Central
            // Portal staging URL. It still requires a Portal token (username
            // = portal token name, password = token value) and GPG signing.
            val ossrhUser = providers.environmentVariable("OSSRH_USERNAME").orNull
            val ossrhPass = providers.environmentVariable("OSSRH_PASSWORD").orNull
            repositories {
                maven {
                    name = "sonatype"
                    val isSnapshot = project.version.toString().endsWith("SNAPSHOT")
                    url = uri(
                        if (isSnapshot) "https://central.sonatype.com/repository/maven-snapshots/"
                        else "https://central.sonatype.com/api/v1/publisher/upload"
                    )
                    if (!ossrhUser.isNullOrBlank() && !ossrhPass.isNullOrBlank()) {
                        credentials {
                            username = ossrhUser
                            password = ossrhPass
                        }
                    }
                }

                // GitHub Packages — works out of the box for tag-triggered
                // CI using the auto-provisioned GITHUB_TOKEN. Lets consumers
                // depend on the library immediately without waiting for
                // Maven Central propagation.
                val ghUser = providers.environmentVariable("GITHUB_ACTOR").orNull
                val ghToken = providers.environmentVariable("GITHUB_TOKEN").orNull
                val ghRepo = providers.environmentVariable("GITHUB_REPOSITORY").orNull
                    ?: "SqLkk/etdmnew"
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$ghRepo")
                    if (!ghUser.isNullOrBlank() && !ghToken.isNullOrBlank()) {
                        credentials {
                            username = ghUser
                            password = ghToken
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
