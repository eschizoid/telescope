import org.jboss.jandex.Indexer
import org.jboss.jandex.IndexWriter

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Runs in the build script's classpath so the custom `jandex` task below can call the
        // smallrye Indexer directly. The kordamp `org.kordamp.gradle.jandex` plugin (2.1.0) is
        // incompatible with Gradle's configuration cache — it serializes a SourceSet field on the
        // task, which CC rejects. Replacing the plugin with a one-shot custom task keeps CC happy
        // and produces a bit-identical META-INF/jandex.idx.
        classpath("io.smallrye:jandex:3.6.0")
    }
}

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
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
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

// Telescope-quarkus is also consumable as a JPMS module via Automatic-Module-Name for downstream
// projects that bring their own non-modular transitive graph; the strict module-info.java published
// in this artifact still wins for consumers on a clean modular classpath.
tasks.jar {
    manifest {
        attributes(mapOf("Automatic-Module-Name" to "io.github.eschizoid.telescope.quarkus"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Custom CC-compatible Jandex indexer. Walks the main compileJava output and writes
// META-INF/jandex.idx into a generated-resources directory that the runtime jar task injects
// directly — not wired through the main resources SourceSet, so the sources jar (from
// `withSourcesJar()`) stays clean. The kordamp plugin replaced here (org.kordamp.gradle.jandex
// 2.1.0) is incompatible with Gradle's configuration cache; this 30-line replacement is fully
// CC-compatible and produces the same META-INF/jandex.idx layout in the published jar.
val jandexOutputDir: Provider<Directory> = layout.buildDirectory.dir("generated/jandex")

val jandex by tasks.registering {
    description = "Generate META-INF/jandex.idx for the main classes"
    group = "build"

    val classesDir: Provider<Directory> = layout.buildDirectory.dir("classes/java/main")
    val indexFile: Provider<RegularFile> = jandexOutputDir.map { it.file("META-INF/jandex.idx") }

    inputs.dir(classesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(indexFile)
    dependsOn("compileJava")

    doLast {
        val indexer = Indexer()
        classesDir.get().asFileTree
            .matching { include("**/*.class") }
            .forEach { classFile ->
                classFile.inputStream().use { input -> indexer.index(input) }
            }
        val out = indexFile.get().asFile
        out.parentFile.mkdirs()
        out.outputStream().use { os -> IndexWriter(os).write(indexer.complete()) }
    }
}

tasks.jar {
    dependsOn(jandex)
    from(jandexOutputDir)
}

tasks.jacocoTestReport {
    // jandex writes META-INF/jandex.idx into the main resources output, which is also a
    // jacocoTestReport classpath input. Gradle's strict task validation refuses to assume the
    // order — declare it explicitly.
    mustRunAfter(jandex)
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Javadoc>().configureEach {
    mustRunAfter(jandex)
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

val quarkusVersion = "3.38.3"

dependencies {
    api(project(":core"))
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
