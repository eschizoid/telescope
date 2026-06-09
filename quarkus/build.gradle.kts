plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    // Quarkus discovers CDI beans via a pre-built Jandex index instead of scanning bytecode at
    // startup. Without this plugin the produced jar has no META-INF/jandex.idx and users see a
    // "Application archive ... is being scanned without a Jandex index" warning + a slower boot.
    id("org.kordamp.gradle.jandex") version "2.1.0"
}

description = "telescope-quarkus — Quarkus CDI extension + Mapper<A,B> bean registry for telescope"

base {
    archivesName = "telescope-quarkus"
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

val quarkusVersion = "3.20.0"

dependencies {
    // Telescope core travels with the extension — consumers of the artifact expect the library to
    // come along with no extra dependency declarations. `api` puts it in the published POM.
    api(project(":core"))

    // Quarkus ArC is the CDI runtime — provides @ApplicationScoped, @Produces, @Inject, and the
    // @All collector annotation used to inject every Mapper<?, ?> bean as a List. `api` so users
    // can write their own @Inject TelescopeMapperRegistry without an extra dep declaration.
    api(platform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))
    api("io.quarkus:quarkus-arc")

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation("org.assertj:assertj-core:3.27.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-quarkus"
            from(components["java"])

            pom {
                name.set("telescope-quarkus")
                description.set(
                    "Quarkus CDI extension + Mapper<A,B> bean registry for the telescope optics DSL."
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
