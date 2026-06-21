plugins {
    java
}

description = "telescope-examples — first downstream consumer; smoke-tests every public entry point end-to-end"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // -parameters lets WriteHint.CONSTRUCTOR match args by parameter name on the immutable POJO demo.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

// One JavaExec per demo so a failing demo trips its own task rather than getting buried in a
// single-JVM stack trace, and so individual demos can be re-run via `:examples:run<Demo>`.
// `:examples:runAllDemos` aggregates them as the canonical smoke-test entry point.
val demos = listOf(
    "RuntimeNavigationDemo",
    "ContainerNavigationDemo",
    "SealedAndFilterDemo",
    "MultiEditDemo",
    "IndexedDemo",
    "EffectfulUpdateDemo",
    "ValidatedMappingDemo",
    "ConversionDemo",
    "DeepMappingDemo",
    "CodegenDemo",
    "LombokDemo",
)

val demoTasks = demos.map { demo ->
    tasks.register<JavaExec>("run$demo") {
        group = "demos"
        description = "Run $demo as a smoke test"
        mainClass.set("io.github.eschizoid.telescope.examples.$demo")
        classpath = sourceSets["main"].runtimeClasspath
    }
}

tasks.register("runAllDemos") {
    group = "demos"
    description = "Run every example demo end-to-end as a smoke test"
    dependsOn(demoTasks)
}

dependencies {
    implementation(project(":core"))
    annotationProcessor(project(":codegen"))

    // Lombok bean integration: the processor lives in :lombok and consumes Lombok's synthesised
    // members. Lombok itself is compile-only — its annotations are SOURCE-retained, so the runtime
    // jar isn't needed.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(project(":lombok"))
}
