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
    // The DSL and the @Focus/@Bridge annotations live in :core. :codegen is the processor that turns those
    // annotations into generated *Focus/*Bridge constants — on the jmh annotation-processor path so it runs
    // over src/jmh and the benchmark measures real generated output, not a hand-rolled stand-in.
    implementation(project(":core"))
    jmhAnnotationProcessor(project(":codegen"))
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
