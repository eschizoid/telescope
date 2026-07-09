plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-lombok — annotation processor that emits <X>Telescope<R> navigators for Lombok @Data/@Value/@Builder classes"

base {
    archivesName = "telescope-lombok"
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
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
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
    api(project(":codegen"))
    api(project(":core"))

    testImplementation(project(":core"))
    testImplementation(project(":codegen"))
    testImplementation(testFixtures(project(":codegen")))
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    // Lombok jar carries the @Data/@Value/@Builder annotation classes alongside the AST-patching
    // processor. testImplementation puts the annotation classes on both the compile classpath (for
    // the file-based fixtures in fixtures/) and the test runtime classpath (so the in-memory
    // ProcessorHarness can resolve `lombok.Data` via `Elements#getTypeElement`). The Lombok
    // processor itself only fires when listed under testAnnotationProcessor below.
    testImplementation(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testAnnotationProcessor(project(":core"))
    testAnnotationProcessor(project(":codegen"))
    testAnnotationProcessor(files(tasks.named("jar")))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-lombok"
            from(components["java"])

            pom {
                name.set("telescope-lombok")
                description.set("Lombok integration for telescope — emits <X>Telescope<R> navigators for @Data/@Value/@Builder classes.")
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
