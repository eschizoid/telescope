plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

// The invoicing submodule demonstrates `@Bridge`-driven codegen for record↔JPA-bean pairs.
// Annotation processors emit the conversion code at compile time, including deep recursion into
// other user-declared bridges (InvoiceHeader's List<InvoiceLine> hops through InvoiceLineBridge
// automatically). Pure compile-time-bound — no Telescope.mapper(...) call at runtime, no
// reflective field-name probe; just direct method calls on the generated bridge classes.

group = "io.github.eschizoid.telescope.demo.invoicing"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

springBoot {
    mainClass.set("io.github.eschizoid.telescope.demo.invoicing.DemoApplication")
}

dependencies {
    implementation(project(":core"))
    annotationProcessor(project(":codegen"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
