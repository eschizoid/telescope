plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-jackson — mappers as Jackson 3 converters, and a streaming JSON binder over jackson-core"

base {
    archivesName = "telescope-jackson"
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
    // The test models are records and beans the verifier has nothing to say about.
    options.compilerArgs.add("-Atelescope.verify=off")
    options.compilerArgs.remove("-Werror")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The binder builds records and beans through the substrate's accessors, which branch to a
// MethodHandle closure inside a native image. NativeImage.IN_IMAGE reads the JDK's imagecode
// property, so setting it runs the same assertions on the accessor substrate an image uses.
val imageTest by tasks.registering(Test::class) {
    description = "Runs the test suite on the MethodHandle accessor substrate a native image uses."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("org.graalvm.nativeimage.imagecode", "runtime")
}

tasks.named("check") {
    dependsOn(imageTest)
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
    // The streaming parser the binder reads with. It is the whole of what the binder needs.
    api(libs.jacksonCore)
    // TelescopeModule is a databind module, so an application that registers one has databind
    // already; one that only binds JSON leaves it out.
    compileOnly(libs.jacksonDatabind)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.jacksonDatabind)
    testAnnotationProcessor(project(":codegen"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-jackson"
            from(components["java"])

            pom {
                name.set("telescope-jackson")
                description.set("Mappers as Jackson 3 converters, and a streaming JSON binder over jackson-core.")
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
