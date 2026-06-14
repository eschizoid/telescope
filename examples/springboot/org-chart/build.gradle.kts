plugins {
    java
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

// The org-chart submodule demonstrates telescope's deep-mapper handling of self-referencing
// record↔entity pairs. A single `Mapper<Employee, EmployeeEntity>` against a bidirectional
// Hibernate graph (manager ↔ reports) exercises both:
//   - Type-level cycle resolution at Telescope.mapper(...) construction time (TypePair cache
//     reserves its slot before recursing into auto-derived component Isos)
//   - Value-level cycle severance at mapper.backward(entityFromDb) time (per-traversal
//     IdentityHashMap seen-set in DeepMap.cycleSafe yields null on re-entry, lifted to
//     Optional.empty() / empty List for the affected slots)

group = "io.github.eschizoid.telescope.demo.orgchart"
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
    mainClass.set("io.github.eschizoid.telescope.demo.orgchart.DemoApplication")
}

dependencies {
    implementation(project(":core"))

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
