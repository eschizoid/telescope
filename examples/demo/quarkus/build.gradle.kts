plugins {
    java
    id("io.quarkus") version "3.40.1"
}

description = "telescope demo — the order service on Quarkus 3, through the Quarkus starter"

// The root project's `:quarkus` module shares this project's name. Gradle tells two projects apart
// by group and name, so this one takes a group of its own.
group = "io.github.eschizoid.telescope.demo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1.1"))
    implementation(project(":examples:demo:domain"))
    implementation(project(":quarkus"))
    // Writes the @TelescopeMapper and @TelescopeTransformer interfaces as CDI beans.
    annotationProcessor(project(":codegen"))

    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest-jackson")

    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
