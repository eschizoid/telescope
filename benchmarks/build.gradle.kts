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

tasks.named<JavaCompile>("compileJmhJava") {
    val jmhCompileClasspath = configurations.named("jmhCompileClasspath")
    doFirst {
        options.compilerArgs.addAll(listOf("--module-path", jmhCompileClasspath.get().asPath))
        classpath = files()
    }
    doLast {
        destinationDirectory.file("module-info.class").get().asFile.delete()
    }
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
