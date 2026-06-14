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

springBoot {
    mainClass.set("io.github.eschizoid.telescope.demo.starter.DemoApplication")
}

dependencies {
    // ONE telescope dependency — the Spring Boot starter pulls in telescope core transitively AND
    // contributes TelescopeAutoConfiguration on startup (which registers a TelescopeMapperRegistry
    // bean that indexes every Mapper<A, B> @Bean by (sourceClass, targetClass)). This submodule
    // is intentionally runtime-only: no @Focus / @BeanFocus / @Bridge annotation processors. The
    // sibling `invoicing/` submodule covers the codegen story.
    implementation(project(":spring-boot-starter"))

    // Lombok itself — only for @Data on the outbound DTO. Telescope-lombok's `:lombok` codegen
    // module is deliberately NOT on the annotation-processor list here.
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
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
