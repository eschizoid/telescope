plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

// Spring Boot 4.0.1 — the latest GA at the time of this demo. Brings Spring Framework 7.0,
// Jakarta EE 10 (`jakarta.persistence.*`), Hibernate 7, and Java 17 baseline. We compile to 25.

group = "io.github.eschizoid.telescope.demo"
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
    mainClass.set("io.github.eschizoid.telescope.demo.spring.DemoApplication")
}

dependencies {
    // Depends on the telescope library *directly* — this submodule wires Mapper<A, B> beans by
    // hand in @Configuration classes (see OrderMappers). The sibling product-starter submodule
    // depends on `:spring-boot-starter` for the same effect via autoconfig + registry.
    implementation(project(":core"))
    annotationProcessor(project(":codegen"))
    annotationProcessor(project(":lombok"))

    // Lombok itself — compile-time for the @Data / @Builder / @Value AST patching, runtime not
    // needed (Lombok generates source-level code). The :lombok telescope module is a graceful
    // no-op when Lombok isn't on the classpath, so this dependency is what actually turns it on.
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    // Spring Boot starters: web for the REST controllers, data-jpa for the repository abstraction,
    // and an H2 in-memory DB so the demo runs without external infra.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("com.h2database:h2")

    // Jackson is pulled in by spring-boot-starter-web; pinning the parameter-names module
    // explicitly so record canonical-constructor deserialization works without surprises.
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    // -parameters lets Spring's @PathVariable / @RequestParam resolve by name without
    // explicit annotation values, and helps Jackson's record-creator detection.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
