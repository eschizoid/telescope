plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

description = "telescope demo — the order service on Spring Boot 4, JPA and H2, through the Spring starter"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

springBoot {
    mainClass.set("io.github.eschizoid.telescope.demo.spring.OrderServiceApplication")
}

dependencies {
    implementation(project(":examples:demo:domain"))
    implementation(project(":spring-boot-starter"))
    // Writes the @TelescopeMapper and @TelescopeTransformer interfaces as Spring components.
    annotationProcessor(project(":codegen"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The Hibernate coverage tests generate a bridge of their own from test sources.
    testAnnotationProcessor(project(":codegen"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The dashboard's statement count is measured twice: by the test task with telescope-jpa on the
// class path, as the starter brings it, and by this task with the module's classes and resources
// taken off, so its container policy is not registered.
val withoutTelescopeJpa = "*WithoutTelescopeJpa*"

tasks.named<Test>("test") {
    filter { excludeTestsMatching(withoutTelescopeJpa) }
}

val testWithoutTelescopeJpa by tasks.registering(Test::class) {
    description = "Runs the dashboard's statement count with telescope-jpa off the class path."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    val jpaBuild = project(":jpa").layout.buildDirectory.get().asFile
    classpath = sourceSets["test"].runtimeClasspath.filter { !it.startsWith(jpaBuild) }
    filter { includeTestsMatching(withoutTelescopeJpa) }
}

tasks.named("check") {
    dependsOn(testWithoutTelescopeJpa)
}
