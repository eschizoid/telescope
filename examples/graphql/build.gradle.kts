plugins {
    java
    id("org.graalvm.buildtools.native") version "1.1.5"
}

description = "telescope-examples-graphql — graphql-java + JDK HttpServer proving runtime Map→POJO via Telescope.fromMap"

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

tasks.register<JavaExec>("runRuntimeFromMapServer") {
    group = "demos"
    description = "Runtime tier: graphql-java server using Telescope.fromMap (reflective; JVM only)"
    mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.RuntimeFromMapServer")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runGeneratedFromMapServer") {
    group = "demos"
    description = "Generated tier: graphql-java server using the @FromMap-generated UserFromMap"
    mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.GeneratedFromMapServer")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runNativeVerify") {
    group = "verification"
    description = "Runs the native-image capability verifier on the JVM — a green run validates the harness; the native-image run is the verdict"
    mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.NativeVerify")
    classpath = sourceSets["main"].runtimeClasspath
}

// GraalVM native build — the entry is NativeVerify, which exercises the full runtime + codegen
// substrate (field update/read, runtime record→record and record→bean mappers, @FromMap, @Bridge)
// and exits non-zero on any mismatch, so the native binary IS the substrate tripwire. native-image
// comes from a GraalVM toolchain (the compile toolchain above stays on the project's JDK 25,
// targeting release 21 bytecode that GraalVM consumes).
graalvmNative {
    // Use the GraalVM pointed to by GRAALVM_HOME/JAVA_HOME for native-image (toolchain detection
    // otherwise picks the project's plain-JDK compile toolchain, which has no native-image).
    toolchainDetection.set(false)
    binaries {
        named("main") {
            mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.NativeVerify")
            imageName.set("telescope-native-verify")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // The telescope classes that back the codegen @Bridge / @FromMap image-heap constants are
            // initialized at build time by telescope-core's own native-image.properties (shipped in
            // the jar) — adopters need no --initialize-at-build-time args for telescope itself. Only
            // this example's generated model package (which holds the AccountBridge / UserFromMap
            // constants) is app-specific and stays here.
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope.examples.graphql.model")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.graphqlJava)
    annotationProcessor(project(":codegen")) // @FromMap → generated UserFromMap / AddressFromMap

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
