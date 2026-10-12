plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-spring-boot-starter — Spring Boot 4 autoconfig + Mapper<A,B> bean registry for telescope"

base {
    archivesName = "telescope-spring-boot-starter"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.jacocoTestReport {
    // TelescopeMapperProcessor lives in :codegen but only runs under this module's in-process compiler
    // tests, where the Spring annotations exist. The :codegen report excludes it; report it here so the
    // lines these tests execute are attributed to it instead of reading as untested.
    val codegen = project(":codegen")
    dependsOn(":codegen:classes")
    classDirectories.from(
        codegen.layout.buildDirectory.dir("classes/java/main").map { dir ->
            fileTree(dir) { include("**/TelescopeMapperProcessor*.class") }
        },
    )
    sourceDirectories.from(codegen.file("src/main/java"))
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

val springBootVersion = "4.1.1"
val springdocVersion = "3.1.1"

dependencies {
    api(project(":core"))
    api("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    // Optional integrations: each is guarded by @ConditionalOnClass, so none reaches a consumer's classpath.
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.data:spring-data-commons")
    compileOnly("org.springdoc:springdoc-openapi-starter-common:$springdocVersion")

    testImplementation(platform(libs.junitBom))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    testImplementation("org.springframework.data:spring-data-commons")
    testImplementation("org.springdoc:springdoc-openapi-starter-common:$springdocVersion")
    testImplementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(testFixtures(project(":codegen")))
    testAnnotationProcessor(project(":codegen"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-spring-boot-starter"
            from(components["java"])

            pom {
                name.set("telescope-spring-boot-starter")
                description.set(
                    "Spring Boot 4 autoconfig + Mapper<A,B> bean registry for the telescope optics DSL."
                )
                url.set("https://github.com/eschizoid/telescope")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("eschizoid")
                        name.set("Mariano Gonzalez")
                        email.set("mariano.gonzalez.mx@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:eschizoid/telescope.git")
                    developerConnection.set("scm:git:git@github.com:eschizoid/telescope.git")
                    url.set("https://github.com/eschizoid/telescope")
                }
            }
        }
    }
    repositories {
        maven {
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey =
        System.getenv("JRELEASER_GPG_SECRET_KEY") ?: project.properties["signing.secretKey"]?.toString()
    val signingPassword =
        System.getenv("JRELEASER_GPG_PASSPHRASE") ?: project.properties["signing.password"]?.toString()
    isRequired = signingKey != null && signingPassword != null
    if (signingKey != null && signingPassword != null) useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

// A named application module that starts the web auto-configuration from the starter's jar, with every jar on the module
// path. The bare run has no Spring Data or springdoc; the full run adds both, which loads the springdoc configuration
// and the Page shape.
val moduleSmoke by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
}

dependencies {
    "moduleSmokeImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    "moduleSmokeImplementation"(project(":core"))
    "moduleSmokeImplementation"("org.springframework.boot:spring-boot-autoconfigure")
    "moduleSmokeImplementation"("org.springframework:spring-webmvc")
    "moduleSmokeImplementation"("org.springframework:spring-test")
    "moduleSmokeImplementation"("jakarta.servlet:jakarta.servlet-api")
    "moduleSmokeImplementation"("tools.jackson.core:jackson-databind")
    "moduleSmokeCompileOnly"("org.springframework.data:spring-data-commons")
    "moduleSmokeCompileOnly"("org.springdoc:springdoc-openapi-starter-common:$springdocVersion")
}

val moduleSmokeFull by configurations.creating {
    extendsFrom(configurations["moduleSmokeRuntimeClasspath"])
}

dependencies {
    moduleSmokeFull("org.springframework.data:spring-data-commons")
    moduleSmokeFull("org.springdoc:springdoc-openapi-starter-common:$springdocVersion")
}

fun JavaExec.moduleSmoke(mode: String, runtime: FileCollection) {
    group = "verification"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    classpath = files(tasks.jar) + moduleSmoke.output + runtime
    mainModule = "telescope.spring.smoke"
    mainClass = "io.github.eschizoid.telescope.spring.smoke.ModuleSmoke"
    args(mode)
    // Spring's jars are automatic modules, which declare no dependencies, so nothing pulls the named modules they load
    // (commons-logging among them) into the graph; the run adds every module on the path.
    jvmArgs("--add-modules", "ALL-MODULE-PATH")
    val marker = layout.buildDirectory.file("module-smoke/$mode.ok")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok") }
}

val moduleSmokeRun by tasks.registering(JavaExec::class) {
    description = "Serves mapped responses with the starter's jar on the module path, without Spring Data or springdoc."
    moduleSmoke("bare", configurations["moduleSmokeRuntimeClasspath"])
}

val moduleSmokeFullRun by tasks.registering(JavaExec::class) {
    description = "Serves mapped responses with the starter's jar on the module path, with Spring Data and springdoc."
    moduleSmoke("full", moduleSmokeFull)
}

tasks.named("check") { dependsOn(moduleSmokeRun, moduleSmokeFullRun) }
