plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-internal — optic lattice, HKT emulation, and reflection helpers (internal to telescope-api)"

base {
    archivesName = "telescope-internal"
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
    // -Xlint:-module suppresses the "qualified-export target module not found" warning that fires
    // because :core (io.github.eschizoid.telescope) requires :internal, not the other way around,
    // so javac compiling :internal can't see :core on the modulepath. Runtime resolution and
    // :core's own compile catch any actual export mistakes.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-module", "-Werror", "-parameters"))
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
    testImplementation(project(":core"))
    testAnnotationProcessor(project(":codegen"))

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-internal"
            from(components["java"])

            pom {
                name.set("telescope-internal")
                description.set(
                    "Internal substrate of the telescope optics DSL: optic lattice (Iso/Lens/Prism/Affine/Traversal), " +
                        "HKT-emulation (Kind/Applicative), per-effect witnesses, and reflection helpers. " +
                        "Qualified-exported to telescope-api only — do NOT depend on this artifact directly."
                )
                url.set("https://github.com/eschizoid/telescope")
                inceptionYear.set("2026")

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
