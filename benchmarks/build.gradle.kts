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
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    modularity.inferModulePath = true
}

// Expose the internal package of :core to the benchmark module at COMPILE time so the LMF
// benchmarks can call Records.read / Beans.readProperty / Beans.settersWriter directly — bypassing
// the public DSL so we time the LambdaMetafactory dispatch primitive in isolation, not a
// composed-lens path. At runtime, JMH bundles everything on the classpath (the benchmark JAR is a
// fat JAR built by the jmh plugin and the JVM is launched with -cp, not --module-path), so all
// classes live in the unnamed module and the export is unnecessary — no runtime --add-exports
// needed.
val internalExportCompileFlag = "--add-exports=io.github.eschizoid.telescope/io.github.eschizoid.telescope.internal=io.github.eschizoid.telescope.benchmarks"

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
}
