plugins {
    java
    jacoco
    id("io.quarkus") version "3.40.1.2"
}

description = "telescope-quarkus tests — Quarkus applications that exercise the extension's build steps and runtime"

// The Quarkus application plugin writes the application model QuarkusUnitTest boots each test
// application from; an extension's own deployment module has none without the Quarkus extension
// plugin. Nothing here is published.

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1.2"))
    implementation(project(":quarkus"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation("io.quarkus:quarkus-junit-internal")
    testImplementation("io.quarkus:quarkus-arc-deployment")
    // Quarkus resolves the deployment artifact through its own application model, which the test
    // task does not track; naming it here makes a change to the build steps rerun the tests.
    testImplementation(project(":telescope-quarkus-deployment"))
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testAnnotationProcessor(project(":codegen"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Any free port: the demo's tests take Quarkus's default test port, and a parallel build runs
    // both modules' tests at once.
    systemProperty("quarkus.http.test-port", "0")
}
