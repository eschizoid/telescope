plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-quarkus-deployment — the build steps of the telescope Quarkus extension"

base {
    archivesName = "telescope-quarkus-deployment"
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
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options,-serial", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

// The build steps run inside the Quarkus applications :telescope-quarkus-tests boots, so their
// coverage is that module's test execution data.
tasks.jacocoTestReport {
    dependsOn(":telescope-quarkus-tests:test")
    executionData(rootProject.file("quarkus/tests/build/jacoco/test.exec"))
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

val quarkusVersion = "3.40.1.2"

dependencies {
    api(project(":quarkus"))
    api(platform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))
    api("io.quarkus:quarkus-arc-deployment")
    implementation("io.quarkus:quarkus-rest-spi-deployment")
    annotationProcessor(platform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))
    annotationProcessor("io.quarkus:quarkus-extension-processor")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-quarkus-deployment"
            from(components["java"])

            pom {
                name.set("telescope-quarkus-deployment")
                description.set(
                    "The build steps of the telescope Quarkus extension: mapped request and response bodies on Quarkus REST."
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
