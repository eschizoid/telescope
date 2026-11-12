plugins {
    java
    id("org.graalvm.buildtools.native") version "1.1.18"
}

description = "telescope demo — a day at the shop as one command, built as a GraalVM native image"

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
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runOnJvm") {
    group = "application"
    description = "Runs the day at the shop on the JVM; nativeRun builds and runs the native image"
    mainClass.set("io.github.eschizoid.telescope.demo.nativeimage.OrderRun")
    classpath = sourceSets["main"].runtimeClasspath
}

graalvmNative {
    // native-image comes from the GraalVM that GRAALVM_HOME or JAVA_HOME points at. Detection would
    // otherwise pick the compile toolchain above, a plain JDK with no native-image.
    toolchainDetection.set(false)
    binaries {
        named("main") {
            mainClass.set("io.github.eschizoid.telescope.demo.nativeimage.OrderRun")
            imageName.set("order-run")
            // Fail rather than fall back to a JVM image, so a missing registration is a build error.
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

dependencies {
    implementation(project(":examples:demo:domain"))
    annotationProcessor(project(":codegen"))

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
