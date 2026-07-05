plugins {
    java
}

description =
    "telescope vs MapStruct — the canonical head-to-head: refactor-safe mapping + deep immutable update"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // -processing keeps MapStruct's own processor notes out of -Xlint; -parameters lets MapStruct
    // and telescope match constructor args by name on the domain records.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

dependencies {
    implementation(project(":core"))

    // Same MapStruct line the :benchmarks head-to-head already pins.
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        // Surface each test's narrated head-to-head (logged through System.Logger -> JUL console),
        // not just a wall of pass/fail ticks.
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Message-only JUL formatting so the narration reads cleanly, without timestamp/source noise.
    systemProperty("java.util.logging.SimpleFormatter.format", "%5\$s%n")
}
