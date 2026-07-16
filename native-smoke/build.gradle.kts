plugins {
    java
    id("org.graalvm.buildtools.native") version "1.1.4"
}

description =
    "telescope-native-smoke — a GraalVM native-image smoke test proving the reflection-free " +
    "LambdaMetafactory / SerializedLambda runtime substrate survives --initialize-at-build-time"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Target release 21 bytecode (as every other module does) so a GraalVM 21+ native-image
    // toolchain consumes it. -parameters lets the bean-mapper's name-based rebuild match cleanly.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
    // Classpath mode on purpose (no module-info.java): the smoke module runs on the plain
    // classpath, which is the simplest, most representative shape a downstream native-image
    // consumer of telescope-core will use. The JPMS module boundary is still exercised at the
    // :core / :internal level upstream.
}

// Plain JVM run of the same main — a fast local sanity check that the harness itself is correct
// (`./gradlew :native-smoke:run`). native-image is what actually proves the substrate; this JVM
// run only proves the smoke logic is green before we hand it to native-image.
tasks.register<JavaExec>("run") {
    group = "verification"
    description = "Run the native smoke main on the JVM (sanity check before the native build)"
    mainClass.set("io.github.eschizoid.telescope.nativesmoke.NativeSmokeMain")
    classpath = sourceSets["main"].runtimeClasspath
}

// GraalVM native build. Running the produced binary IS the test — it exits non-zero on any
// capability failure, so `nativeRun` failing fails the CI job.
graalvmNative {
    // Use the GraalVM pointed to by GRAALVM_HOME / JAVA_HOME for native-image rather than letting
    // toolchain detection pick the plain compile JDK (which has no native-image). Mirrors the
    // :examples:graphql setup.
    toolchainDetection.set(false)
    binaries {
        named("main") {
            imageName.set("telescope-native-smoke")
            mainClass.set("io.github.eschizoid.telescope.nativesmoke.NativeSmokeMain")
            // --no-fallback: fail the build instead of silently emitting a JVM-fallback image if
            //   any reachability gap is hit. This is what makes the smoke test honest — a fallback
            //   image would "pass" while hiding the exact native-image incompatibility we're testing.
            buildArgs.add("--no-fallback")
            // Report the reason for any build-time initialization decision if the build fails, so a
            // failing run tells us WHICH class the substrate couldn't initialize at build time.
            buildArgs.add("--initialize-at-build-time=io.github.eschizoid.telescope.nativesmoke")
            // Surface an actionable trace if native-image cannot fold something the substrate needs.
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

dependencies {
    implementation(project(":core"))
    // @Bridge(SmokeBeanB.class) on SmokeBeanA drives the :codegen processor to emit
    // SmokeBeanABridge — the codegen control path in the smoke test.
    annotationProcessor(project(":codegen"))
}
