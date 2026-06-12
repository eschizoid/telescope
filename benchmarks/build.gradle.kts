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
    implementation(project(":core"))
    jmhAnnotationProcessor(project(":codegen"))
    jmhAnnotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    jmhImplementation("org.mapstruct:mapstruct:1.6.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    modularity.inferModulePath = true
}

val internalExportCompileFlag = "--add-exports=io.github.eschizoid.telescope.internal/io.github.eschizoid.telescope.internal=io.github.eschizoid.telescope.benchmarks"

tasks.named<JavaCompile>("compileJmhJava") {
    val jmhCompileClasspath = configurations.named("jmhCompileClasspath")
    doFirst {
        options.compilerArgs.addAll(listOf("--module-path", jmhCompileClasspath.get().asPath, internalExportCompileFlag))
        classpath = files()
    }
    doLast {
        destinationDirectory.file("module-info.class").get().asFile.delete()
    }
}

jmh {
    warmupIterations = (project.findProperty("jmh.warmup") as String? ?: "3").toInt()
    iterations = (project.findProperty("jmh.iterations") as String? ?: "5").toInt()
    fork = (project.findProperty("jmh.fork") as String? ?: "1").toInt()
    threads = 1
    benchmarkMode = listOf("avgt")
    timeUnit = "ns"
    failOnError = true
    // Optional filter — `-Pjmh.includes=HolderDispatchBenchmark` runs only matching benchmarks.
    (project.findProperty("jmh.includes") as String?)?.let { includes = listOf(it) }
    // Optional per-iteration time overrides — `-Pjmh.timeOnIteration=2s -Pjmh.warmupTime=2s` for quick smoke runs.
    (project.findProperty("jmh.timeOnIteration") as String?)?.let { timeOnIteration = it }
    (project.findProperty("jmh.warmupTime") as String?)?.let { warmup = it }
}
