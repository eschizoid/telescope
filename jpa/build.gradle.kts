plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-jpa — mapping JPA entities without loading the lazy collections the mapping does not read"

base {
    archivesName = "telescope-jpa"
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
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-Werror", "-parameters"))
}

tasks.named<JavaCompile>("compileTestJava") {
    // The test entities are JPA beans the verifier has nothing to say about, and Hibernate's own
    // annotations are not on the processor path.
    options.compilerArgs.add("-Atelescope.verify=off")
    options.compilerArgs.remove("-Werror")
}

// The suites that hold the module to its effect run with the module on the class path; the one
// that measures what a mapping costs without it runs with this module's classes and resources
// taken off, so neither of its providers is registered.
val withoutTheModule = "*WithoutTheModuleTest*"

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    filter { excludeTestsMatching(withoutTheModule) }
}

val testWithoutTheModule by tasks.registering(Test::class) {
    description = "Runs the comparison suite with this module's providers off the class path."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath - sourceSets["main"].output
    filter { includeTestsMatching(withoutTheModule) }
}

// The proxy accessors branch to a MethodHandle closure inside a native image, as every runtime
// LambdaMetafactory site does. NativeImage.IN_IMAGE reads the JDK's imagecode property, so setting
// it runs the same assertions on the accessor substrate an image uses.
val imageTest by tasks.registering(Test::class) {
    description = "Runs the test suite on the MethodHandle accessor substrate a native image uses."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("org.graalvm.nativeimage.imagecode", "runtime")
    filter { excludeTestsMatching(withoutTheModule) }
}

tasks.named("check") {
    dependsOn(testWithoutTheModule, imageTest)
}

tasks.jacocoTestReport {
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

dependencies {
    api(project(":core"))
    // The Jakarta Persistence API alone, and only at compile time: an application with JPA brings
    // it, and one without it gets a module whose providers stay inactive.
    compileOnly(libs.jakartaPersistenceApi)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.hibernateCore)
    testRuntimeOnly(libs.h2)
    testAnnotationProcessor(project(":codegen"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-jpa"
            from(components["java"])

            pom {
                name.set("telescope-jpa")
                description.set("Mapping JPA entities without loading the lazy collections the mapping does not read.")
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
