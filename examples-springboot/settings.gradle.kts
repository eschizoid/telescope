rootProject.name = "telescope-examples-springboot"

// Composite build — the demo is a separate Gradle build but pulls telescope's library modules from
// the sibling source tree. Gradle substitutes the `io.github.eschizoid:telescope` and
// `io.github.eschizoid:telescope-codegen` dependencies declared in build.gradle.kts with the local
// projects automatically. Iteration cycle: edit telescope source → re-run demo tests → see effect.
// No publish-to-Maven-Central round-trip per change.
//
// When we cut a real public 1.0 demo, swap this to depend on the published artifact instead.
includeBuild("../") {
    dependencySubstitution {
        substitute(module("io.github.eschizoid:telescope")).using(project(":core"))
        substitute(module("io.github.eschizoid:telescope-codegen")).using(project(":codegen"))
        substitute(module("io.github.eschizoid:telescope-lombok")).using(project(":lombok"))
    }
}
