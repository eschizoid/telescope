plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

description = "telescope-core — deep-copy DSL for Java records and POJOs"

base {
    archivesName = "telescope-core"
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
    // `-Xlint:all` minus the categories that report on how javac was invoked rather than on the
    // code. `options` is not one `all` switches on -- it is on by default, and naming it here is
    // the only way off -- and it is the one that matters beside `-Werror`, because it fires on the
    // release level this file picks. The lowest release level javac still supports rises every few
    // versions and thereafter refuses anything below it outright, so on the day the lowest reaches the
    // level pinned above, this module stops compiling with a message about source levels and
    // nothing to fix in its source.
    //
    // The rest of `all` stays on, and stays an open set deliberately -- a toolchain bump finding a
    // category nobody had seen is the point of the flag. The root build checks that no module pairs
    // `-Werror` with an unsuppressed `options`, so adding the flag elsewhere cannot inherit this.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-Werror", "-parameters"))
}

tasks.named<JavaCompile>("compileTestJava") {
    // This suite deliberately constructs invalid mappers to pin the construction-time rejections;
    // the compile-time verifier would (correctly) refuse to compile them. The module under test IS
    // the construction-time validator, so compile-time verification is off for its own tests.
    options.compilerArgs.add("-Atelescope.verify=off")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Every runtime LambdaMetafactory site branches to a MethodHandle closure inside a native image,
// and that branch is taken by no test — so the two substrates can report the same condition
// differently and the suite stays green either way. NativeImage.IN_IMAGE reads the JDK's imagecode
// property, so setting it runs the existing assertions on the accessor substrate an image uses.
//
// This proves the two substrates agree. It is not a native-image run: there is no closed world, no
// reachability metadata and no SerializedLambda restriction here.
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
    // telescope-internal carries the optic lattice + HKT emulation + reflection helpers.
    // `api` so :core's public types (Telescope, Mapper) can reference internal optic types in their
    // bodies without leaking them at module exports — the qualified-export in :internal's
    // module-info confines visibility to :core only.
    api(project(":internal"))

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainersJunit)
    testRuntimeOnly(libs.slf4jSimple)
    // Run the @Focus processor over test sources so we can verify generated *Focus classes end-to-end.
    testAnnotationProcessor(project(":codegen"))
    // The container-allocation gate drives the real BridgeProcessor rather than a restatement of
    // what it does, which needs the in-memory compilation harness. A test-only edge: :codegen's
    // main already depends on :core's main, and this is :core's test depending on :codegen's, so
    // the two never meet in a cycle.
    testImplementation(testFixtures(project(":codegen")))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.eschizoid"
            artifactId = "telescope-core"
            from(components["java"])

            pom {
                name.set("telescope-core")
                description.set("Telescope — a deep-copy DSL for Java records and POJOs.")
                url.set("https://github.com/eschizoid/telescope")
                inceptionYear.set("2025")

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
