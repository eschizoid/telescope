plugins {
    java
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

// The product-starter submodule demonstrates depending on telescope through the Spring Boot
// starter — one dependency line gets you the library + the @AutoConfiguration that wires the
// TelescopeMapperRegistry. The registry indexes every Mapper<A, B> @Bean in the context by
// (sourceClass, targetClass), enabling polymorphic dispatch from a single generic service.

group = "io.github.eschizoid.telescope.demo.starter"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ONE telescope dependency — the Spring Boot starter pulls in telescope core transitively AND
    // contributes TelescopeAutoConfiguration on startup (which registers a TelescopeMapperRegistry
    // bean that indexes every Mapper<A, B> @Bean by (sourceClass, targetClass)). The codegen
    // processors are still separate (compile-time-only, not transitive).
    implementation(project(":spring-boot-starter"))
    annotationProcessor(project(":codegen"))
    annotationProcessor(project(":lombok"))

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
