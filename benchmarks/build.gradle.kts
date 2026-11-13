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

val jpaJar: Configuration = configurations.create("jpaJar") {
    isTransitive = false
}

// telescope-jpa's classes without the module's META-INF/services entries: on the benchmarks' class
// path its providers would register themselves for every benchmark, not just the one measuring them.
val jpaUnregistered = tasks.register<Jar>("jpaUnregistered") {
    archiveFileName = "telescope-jpa-unregistered.jar"
    destinationDirectory = layout.buildDirectory.dir("jpa-unregistered")
    val jars: FileCollection = jpaJar
    // Read inside a closure the archive lists no producer, so the jar is built first by name.
    dependsOn(jars)
    from({ jars.map { zipTree(it) } }) {
        exclude("META-INF/services/**")
    }
}

dependencies {
    implementation(project(":core"))
    jmhImplementation(project(":spring-boot-starter")) {
        // The starter brings telescope-jpa with its registrations; the copy below is the one wanted.
        exclude(module = "jpa")
    }
    jmhImplementation(project(":jackson"))
    jmhImplementation(libs.jacksonDatabind)
    jmhAnnotationProcessor(project(":codegen"))
    jmhAnnotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    jmhImplementation("org.mapstruct:mapstruct:1.6.3")
    // LoadCheckBenchmark instantiates telescope-jpa's container policy itself, from a copy of the
    // module's jar without its service registrations (below), so every other benchmark copies
    // through the standard policy as before. Hibernate is there so the persistence utility the
    // policy calls asks a real provider.
    jpaJar(project(":jpa"))
    jmhRuntimeOnly(files(jpaUnregistered))
    jmhRuntimeOnly(libs.jakartaPersistenceApi)
    jmhRuntimeOnly(libs.hibernateCore)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    modularity.inferModulePath = true
}

val internalExportCompileFlag = "--add-exports=io.github.eschizoid.telescope.internal/io.github.eschizoid.telescope.internal=io.github.eschizoid.telescope.benchmarks"

tasks.named<JavaCompile>("compileJmhJava") {
    // A task action may only close over values the configuration cache can serialise, and a
    // provider for a Configuration is not one. A Configuration is itself a FileCollection, which
    // is, so the resolution stays lazy while what the action captures stays serialisable.
    val jmhModulePath: FileCollection = configurations.getByName("jmhCompileClasspath")
    val exportFlag = internalExportCompileFlag
    val emptyClasspath: FileCollection = objects.fileCollection()
    doFirst {
        options.compilerArgs.addAll(listOf("--module-path", jmhModulePath.asPath, exportFlag))
        classpath = emptyClasspath
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
    (project.findProperty("jmh.profilers") as String?)?.let { profilers = it.split(",") }
    // Optional flags for every forked benchmark JVM, space-separated -- for example
    // `-Pjmh.jvmArgsAppend="-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"` to read inlining decisions.
    (project.findProperty("jmh.jvmArgsAppend") as String?)?.let { args ->
        jvmArgsAppend = args.split(" ").filter { it.isNotBlank() }
    }
}

// The benchmarks live in the jmh source set, which `check` does not reach: this module's own
// compileJava and compileTestJava have no sources, so `check` completes without ever compiling a
// benchmark. A benchmark that no longer matches the code it measures therefore ships, and is
// discovered only when someone dispatches a run by hand -- which is how one of them spent an
// unknown stretch throwing out of @Setup and measuring nothing at all.
//
// Compiling is the whole of what is wanted here. Running them on a shared runner would produce
// numbers worth nothing, and would cost minutes per pull request.
tasks.named("check") {
    dependsOn("jmhClasses")
}
