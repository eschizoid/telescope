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
        classpath("io.smallrye:jandex:3.6.1")
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
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-parameters"))
}

// The Quarkus REST and Hibernate Validator jars carry no module name, so Gradle puts them on the
// class path. The classes of the mapped REST bodies compile against them by reading the unnamed
// module; a Quarkus application runs on the class path, where the module descriptor is not read.
val readsUnnamed = listOf("--add-reads", "io.github.eschizoid.telescope.quarkus=ALL-UNNAMED")
tasks.compileJava {
    options.compilerArgs.addAll(readsUnnamed)
}

// The runtime jar's extension descriptor names the deployment artifact, which Quarkus resolves at
// build time to run the build steps of the mapped REST bodies. Inside this build, the Quarkus
// application plugin finds the deployment project instead through an extension named
// quarkusExtension, read by these getter names. The Quarkus extension plugin that adds both cannot
// be stored in the configuration cache, so this build declares them itself.
interface LocalQuarkusExtension {
    val deploymentModule: Property<String>
    val conditionalDependencies: ListProperty<String>
    val conditionalDevDependencies: ListProperty<String>
    val dependencyConditions: ListProperty<String>
}

extensions.create<LocalQuarkusExtension>("quarkusExtension").deploymentModule.set(":telescope-quarkus-deployment")

val extensionDescriptorDir: Provider<Directory> = layout.buildDirectory.dir("generated/extension-descriptor")

val extensionDescriptor by tasks.registering {
    description = "Generate META-INF/quarkus-extension.properties naming the deployment artifact"
    group = "build"

    val coordinates = "io.github.eschizoid:telescope-quarkus-deployment:${project.version}"
    val descriptor: Provider<RegularFile> =
        extensionDescriptorDir.map { it.file("META-INF/quarkus-extension.properties") }
    inputs.property("coordinates", coordinates)
    outputs.file(descriptor)

    doLast {
        val out = descriptor.get().asFile
        out.parentFile.mkdirs()
        out.writeText("deployment-artifact=" + coordinates.replace(":", "\\:") + "\n")
    }
}

tasks.jar {
    dependsOn(extensionDescriptor)
    from(extensionDescriptorDir)
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
    // The mapped REST bodies run inside the Quarkus applications :telescope-quarkus-tests boots, so
    // that module's test execution data counts toward this module's classes too.
    dependsOn(":telescope-quarkus-tests:test")
    executionData(file("tests/build/jacoco/test.exec"))
    // TelescopeMapperProcessor lives in :codegen, and only this module's tests compile and run the
    // CDI beans it writes against the real CDI and ArC APIs. A report covers only the classes it
    // lists, so it is listed here too, and the lines these tests execute count toward it beside the
    // :codegen report's.
    val codegen = project(":codegen")
    dependsOn(":codegen:classes")
    classDirectories.from(
        codegen.layout.buildDirectory.dir("classes/java/main").map { dir ->
            fileTree(dir) { include("**/TelescopeMapperProcessor*.class") }
        },
    )
    sourceDirectories.from(codegen.file("src/main/java"))
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
        addStringOption("-add-reads", "io.github.eschizoid.telescope.quarkus=ALL-UNNAMED")
    }
}

val quarkusVersion = "3.40.1.2"

dependencies {
    api(project(":core"))
    api(platform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))
    // telescope-jpa registers itself through core's ServiceLoader SPIs and stays inactive unless
    // jakarta.persistence is on the class path, so an application with JPA gets it with no setup. It is
    // on the compile class path too, where the processor reads which properties it generates.
    api(project(":jpa"))
    api("io.quarkus:quarkus-arc")
    // Mapped REST bodies: present only in an application that uses Quarkus REST, and validated
    // only in one that uses Hibernate Validator.
    compileOnly("io.quarkus:quarkus-rest")
    compileOnly("io.quarkus:quarkus-hibernate-validator")

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("io.quarkus:quarkus-junit-component")
    testImplementation(testFixtures(project(":codegen")))
    testAnnotationProcessor(project(":codegen"))
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

// A consumer that depends on the starter alone: the README's entities and edit mappers, compiled with the processor
// against the class path the starter's published dependencies give a consumer. Compiling them is the check that the
// generated identifiers and the back-reference need no row there, which takes telescope-jpa at compile time.
val consumer by sourceSets.creating {
    // The same sources as the Spring starter's consumer, so both starters are held to one hero.
    java.srcDir(rootProject.file("spring-boot-starter/src/consumer/java"))
}

dependencies {
    "consumerImplementation"(project(path))
    "consumerCompileOnly"(libs.jakartaPersistenceApi)
    "consumerAnnotationProcessor"(project(":codegen"))
}

tasks.named("check") { dependsOn(tasks.named("compileConsumerJava")) }
