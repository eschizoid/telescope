plugins {
    java
    alias(libs.plugins.jmh)
}

description = "telescope-benchmarks — JMH micro-benchmarks validating the reflection vs reflection-free claims"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Reflection path (Telescope.of(...).field(...)) and the reflection-free Telescope.lens(...) constant
    // both live in :core. :codegen is the @Focus processor that emits those lens constants — we depend on it
    // so the benchmark module mirrors the real consumer setup, even though the hand-rolled Telescope.lens
    // constant stands in for generated output here.
    implementation(project(":core"))
    implementation(project(":codegen"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

jmh {
    warmupIterations = 3
    iterations = 5
    fork = 1
    threads = 1
    benchmarkMode = listOf("avgt")
    timeUnit = "ns"
    failOnError = true
}
