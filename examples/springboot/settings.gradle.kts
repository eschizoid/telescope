rootProject.name = "examples-springboot"

// This is a standalone composite build, nested two levels deep under the telescope repo at
// `examples/springboot/`. It can't be a subproject of the parent telescope build because Spring
// Boot's Gradle plugin classpath doesn't compose with telescope's parent build conventions, so it
// runs as its own Gradle root with a composite-build link back to the telescope library modules.
//
// Two submodules under one parent — two ways to depend on telescope from a Spring Boot app:
//   order-jpa       depends on `io.github.eschizoid:telescope` directly (the library), wires
//                   Mapper<A, B> beans by hand. JPA + Hibernate + Jackson + Lombok end-to-end.
//   product-starter depends on `io.github.eschizoid:telescope-spring-boot-starter` (autoconfig +
//                   TelescopeMapperRegistry). Same Lombok + Jackson + JPA stack, demonstrates the
//                   registry-driven polymorphic dispatch.
include("order-jpa")
include("product-starter")

// Composite build — both submodules pull telescope's library modules from the parent repo's source
// tree (`../../`, since this build lives at `examples/springboot/`). Gradle substitutes the
// `io.github.eschizoid:telescope[-*]` dependencies with the local projects automatically.
// Iteration cycle: edit telescope source → re-run a submodule's tests → see effect. No
// publish-to-Maven-Central round-trip per change.
includeBuild("../../") {
    dependencySubstitution {
        substitute(module("io.github.eschizoid:telescope")).using(project(":core"))
        substitute(module("io.github.eschizoid:telescope-codegen")).using(project(":codegen"))
        substitute(module("io.github.eschizoid:telescope-lombok")).using(project(":lombok"))
        substitute(module("io.github.eschizoid:telescope-spring-boot-starter"))
            .using(project(":spring-boot-starter"))
    }
}
