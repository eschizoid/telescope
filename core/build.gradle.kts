plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-api — deep-copy DSL for Java records and POJOs"

base {
    archivesName = "telescope-api"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.jacocoTestReport {
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

dependencies {
    // telescope-internal carries the optic lattice + HKT emulation + reflection helpers.
    // `api` so :core's public types (Telescope, Mapper) can reference internal optic types in their
    // bodies without leaking them at module exports — the qualified-export in :internal's
    // module-info confines visibility to :core only.
    api(project(":internal"))

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainersJunit)
    testRuntimeOnly(libs.slf4jSimple)
    // Run the @Focus processor over test sources so we can verify generated *Focus classes end-to-end.
    testAnnotationProcessor(project(":codegen"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-api"
            from(components["java"])

            pom {
                name.set("telescope-api")
                description.set("Public DSL surface of telescope — deep-copy DSL for Java records and POJOs.")
                url.set("https://github.com/eschizoid/telescope")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("eschizoid")
                        name.set("Mariano Gonzalez")
                        email.set("mariano.gonzalez.mx@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:eschizoid/telescope.git")
                    developerConnection.set("scm:git:git@github.com:eschizoid/telescope.git")
                    url.set("https://github.com/eschizoid/telescope")
                }
            }
        }
    }
    repositories {
        maven {
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey =
        System.getenv("JRELEASER_GPG_SECRET_KEY") ?: project.properties["signing.secretKey"]?.toString()
    val signingPassword =
        System.getenv("JRELEASER_GPG_PASSPHRASE") ?: project.properties["signing.password"]?.toString()
    isRequired = signingKey != null && signingPassword != null
    if (signingKey != null && signingPassword != null) useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}
