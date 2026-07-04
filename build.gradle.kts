import com.diffplug.gradle.spotless.SpotlessExtension
import org.jreleaser.model.Active.ALWAYS
import org.jreleaser.model.Active.NEVER
import pl.allegro.tech.build.axion.release.VerifyReleaseTask

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.axion)
    alias(libs.plugins.jreleaser)
}

buildscript {
    dependencies {
        classpath(libs.jgitGpgBc)
        classpath(libs.jgit)
    }
    configurations.classpath {
        resolutionStrategy {
            force(libs.jgitGpgBc)
            force(libs.jgit)
        }
    }
}

scmVersion {
    unshallowRepoOnCI.set(true)
    tag {
        prefix.set("v")
    }
    versionCreator("versionWithBranch")
    branchVersionCreator.set(
        mapOf(
            "main" to "simple",
        ),
    )
    val incrementType =
        when (project.findProperty("release.incrementer")?.toString()) {
            "patch" -> "incrementPatch"
            "minor" -> "incrementMinor"
            "major" -> "incrementMajor"
            else -> "incrementMinor"
        }
    versionIncrementer(incrementType)
    branchVersionIncrementer.set(
        mapOf(
            "feature/.*" to "incrementMinor",
            "bugfix/.*" to "incrementPatch",
        ),
    )
}

allprojects {
    group = "io.github.eschizoid"
    version = rootProject.scmVersion.version
}

// The snapshot-dependency scan resolves every subproject's configurations from the root
// verifyRelease task; the GraalVM native-buildtools plugin (:examples:graphql) forbids that
// cross-project resolution ("attempted without an exclusive lock"), failing every release run at
// configuration-cache store time. Replacing the convention provider with an empty set neutralizes
// only the scan — the task's uncommitted-changes / ahead-of-remote guards keep running. Safe here:
// every dependency is a catalog-pinned release version, so the SNAPSHOT guard has nothing to catch.
tasks.withType<VerifyReleaseTask>().configureEach {
    snapshotDependencies.empty()
}

// Single source of truth for which subprojects ship to Maven Central. Used below to drive both
// JReleaser staging-repository discovery AND surfaced in the release workflow's diagnostic.
// When you add a new published module:
//   1) apply `maven-publish` + configure its `publishing { publications { ... } }` block, and
//   2) add its project path to this list.
// The release workflow runs `./gradlew clean build publish -x test` which fans out to every
// subproject's `publish` task via maven-publish, so step (1) wires up the workflow side for
// free — the only manual touch left is this list.
val mavenCentralProjects = listOf(":internal", ":core", ":codegen", ":lombok", ":spring-boot-starter", ":quarkus")

// Root owns markdown + the Gradle Kotlin DSL formatting (no Java source here).
spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("markdown") {
        target("**/*.md")
        targetExclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
        prettier(
            mapOf(
                "prettier" to libs.versions.prettier.get(),
                "prettier-plugin-java" to libs.versions.prettierPluginJava.get(),
            ),
        ).config(
            mapOf(
                "plugins" to listOf("prettier-plugin-java"),
                "printWidth" to 120,
                "proseWrap" to "always",
                "tabWidth" to 2,
            ),
        )
    }
}

subprojects {
    if (path == ":examples" || path == ":examples:springboot") return@subprojects
    apply(plugin = "com.diffplug.spotless")
    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(libs.versions.googleJavaFormat.get())
            toggleOffOn()
            importOrder()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            prettier(
                mapOf(
                    "prettier" to libs.versions.prettier.get(),
                    "prettier-plugin-java" to libs.versions.prettierPluginJava.get(),
                ),
            ).config(
                mapOf(
                    "plugins" to listOf("prettier-plugin-java"),
                    "parser" to "java",
                    "tabWidth" to 2,
                    "printWidth" to 120,
                ),
            )
        }
    }
}

jreleaser {
    gitRootSearch.set(true)

    project {
        name.set("telescope")
        description.set("Deep-copy DSL for Java records and POJOs.")
        authors.set(listOf("Mariano Gonzalez"))
        license.set("Apache-2.0")
        links {
            homepage.set("https://github.com/eschizoid/telescope")
        }
        inceptionYear.set("2026")
        tags.set(listOf("records", "optics", "lens", "immutable", "java"))
    }

    // Signing is delegated to Gradle's `signing` plugin (configured per-module via each module's
    // build file). JReleaser only handles deployment, so its signing stage is intentionally disabled.
    signing {
        active.set(NEVER)
    }

    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    // Driven by the single `mavenCentralProjects` list above so we don't
                    // hardcode the same module set twice.
                    mavenCentralProjects.forEach { path ->
                        stagingRepository(
                            project(path).layout.buildDirectory
                                .dir("staging-deploy")
                                .get()
                                .asFile.absolutePath,
                        )
                    }
                    enabled.set(true)
                    sign.set(false)
                    maxRetries.set(60)
                    retryDelay.set(60)
                    extraProperties.put("retryOnAlreadyDeployed", true)
                }
            }
        }
    }

    release {
        github {
            enabled.set(true)
            overwrite.set(true)
            draft.set(false)
            prerelease {
                enabled.set(false)
            }
            changelog {
                formatted.set(ALWAYS)
                preset.set("conventional-commits")
            }
        }
    }
}
