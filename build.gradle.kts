import com.diffplug.gradle.spotless.SpotlessExtension
import org.jreleaser.model.Active.ALWAYS
import org.jreleaser.model.Active.NEVER

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

// Java formatting belongs to the modules that actually have Java sources. Skip pure container
// projects (`:examples`, `:examples:springboot`) — they just hold child subprojects, no own sources.
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
                    stagingRepository(
                        project(":core").layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.absolutePath,
                    )
                    stagingRepository(
                        project(":codegen").layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.absolutePath,
                    )
                    stagingRepository(
                        project(":lombok").layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.absolutePath,
                    )
                    stagingRepository(
                        project(":spring-boot-starter").layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.absolutePath,
                    )
                    stagingRepository(
                        project(":quarkus").layout.buildDirectory
                            .dir("staging-deploy")
                            .get()
                            .asFile.absolutePath,
                    )
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
