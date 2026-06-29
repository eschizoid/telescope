plugins {
    java
    id("org.graalvm.buildtools.native") version "1.1.3"
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
    description = "Generated tier: graphql-java server using the @FromMap-generated UserFromMap — the entry the native image builds"
    mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.GeneratedFromMapServer")
    classpath = sourceSets["main"].runtimeClasspath
}

// GraalVM native build — proves the generated @FromMap converter (via GeneratedFromMapServer)
// native-images with zero reachability config. native-image comes from a GraalVM toolchain (the
// compile toolchain above stays on the project's JDK 25, targeting release 21 bytecode that GraalVM
// 21 consumes).
graalvmNative {
    // Use the GraalVM pointed to by GRAALVM_HOME/JAVA_HOME for native-image (toolchain detection
    // otherwise picks the project's plain-JDK compile toolchain, which has no native-image).
    toolchainDetection.set(false)
    binaries {
        named("main") {
            mainClass.set("io.github.eschizoid.telescope.examples.graphql.server.GeneratedFromMapServer")
            buildArgs.add("--no-fallback")
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
