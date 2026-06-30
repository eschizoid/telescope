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
    // and telescope match constructor args by name on the record DTOs.
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
}
