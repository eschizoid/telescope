plugins {
    java
    id("org.graalvm.buildtools.native") version "1.1.4"
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
            // The codegen @Bridge / @FromMap constants (e.g. AccountBridge.BRIDGE — a
            // Telescope<Account, AccountEntity>) are baked into the image heap at class-init.
            // native-image defaults every class to run-time init, so the telescope classes that
            // back those constants (Telescope + its inner Bridge/typed-container navigators, the
            // optic lattice) and the generated model classes that hold them must be initialized at
            // build time — otherwise a heap object of a run-time-init type is a hard build error.
            //
            // The four entries are listed explicitly rather than collapsed to the
            // `io.github.eschizoid.telescope` prefix on purpose: the prefix subsumes the others only
            // if native-image treats a package arg as a recursive prefix, and until the first green
            // CI run pins that behaviour the belt-and-suspenders list is the safe form. Keep them
            // explicit — do not "optimize" to one line before the workflow has run green.
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope.examples.graphql.model")
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope")
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope.internal")
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope.internal.optics")
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
