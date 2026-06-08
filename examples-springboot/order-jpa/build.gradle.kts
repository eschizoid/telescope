plugins {
    java
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.0"
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
    mavenLocal() // For consuming locally-published telescope snapshots during demo development.
}

dependencies {
    // Depends on the telescope library *directly* — this submodule wires Mapper<A, B> beans by
    // hand in @Configuration classes (see OrderMappers). The sibling product-starter submodule
    // depends on telescope-spring-boot-starter for the same effect via autoconfig + registry.
    implementation("io.github.eschizoid:telescope:0.4.1")
    annotationProcessor("io.github.eschizoid:telescope-codegen:0.4.1")
    annotationProcessor("io.github.eschizoid:telescope-lombok:0.4.1")

    // Lombok itself — compile-time for the @Data / @Builder / @Value AST patching, runtime not
    // needed (Lombok generates source-level code). The :lombok telescope module is a graceful
    // no-op when Lombok isn't on the classpath, so this dependency is what actually turns it on.
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

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
    options.release = 25
    options.encoding = "UTF-8"
    // -parameters lets Spring's @PathVariable / @RequestParam resolve by name without
    // explicit annotation values, and helps Jackson's record-creator detection.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Mirrors the :core / :codegen / :lombok spotless config in the parent telescope project:
// googleJavaFormat for baseline formatting + prettier-plugin-java for final pass at 120 cols.
spotless {
    java {
        target("src/**/*.java")
        targetExclude("build/generated/**")
        googleJavaFormat("1.35.0")
        toggleOffOn()
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        prettier(
            mapOf(
                "prettier" to "3.8.1",
                "prettier-plugin-java" to "2.8.1",
            ),
        ).config(
            mapOf(
                "plugins" to listOf("prettier-plugin-java"),
                "parser" to "java",
                "tabWidth" to 2,
                "printWidth" to 120,
            ),
        )
    }
}
