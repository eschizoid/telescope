plugins {
    java
    application
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
    options.release = 25
    options.encoding = "UTF-8"
    // -parameters lets WriteHint.CONSTRUCTOR match args by parameter name on the immutable POJO demo.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

application {
    mainClass.set("io.github.eschizoid.telescope.examples.Main")
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
