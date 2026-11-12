plugins {
    `java-library`
}

description = "telescope demo — the order service's entities, DTOs and mappers, shared by every app"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    api(project(":core"))
    // The entities carry their JPA mapping. The Spring app reads it; elsewhere it is plain metadata.
    api("jakarta.persistence:jakarta.persistence-api:3.2.0")
    // Each app brings its own Jackson; the domain needs only the annotations that name the payment
    // cases on the wire.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.20")
    annotationProcessor(project(":codegen"))

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation("org.assertj:assertj-core:3.27.7")
    // The verifier test compiles a broken mapper against these classes with the processor.
    testImplementation(project(":codegen"))
    testImplementation(testFixtures(project(":codegen")))
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations:2.20")
}
