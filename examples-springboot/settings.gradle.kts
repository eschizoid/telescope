rootProject.name = "examples-springboot"

// Two submodules under one parent — two ways to depend on telescope from a Spring Boot app:
//   order-jpa       depends on `io.github.eschizoid:telescope` directly (the library), wires
//                   Mapper<A, B> beans by hand. JPA + Hibernate + Jackson + Lombok end-to-end.
//   product-starter depends on `io.github.eschizoid:telescope-spring-boot-starter` (autoconfig +
//                   TelescopeMapperRegistry). Same Lombok + Jackson + JPA stack, demonstrates the
//                   registry-driven polymorphic dispatch.
include("order-jpa")
include("product-starter")

// Composite build — both submodules pull telescope's library modules from the sibling source tree.
// Gradle substitutes the `io.github.eschizoid:telescope[-*]` dependencies with the local projects
// automatically. Iteration cycle: edit telescope source → re-run a submodule's tests → see effect.
// No publish-to-Maven-Central round-trip per change.
includeBuild("../") {
    dependencySubstitution {
        substitute(module("io.github.eschizoid:telescope")).using(project(":core"))
        substitute(module("io.github.eschizoid:telescope-codegen")).using(project(":codegen"))
        substitute(module("io.github.eschizoid:telescope-lombok")).using(project(":lombok"))
        substitute(module("io.github.eschizoid:telescope-spring-boot-starter"))
            .using(project(":spring-boot-starter"))
    }
}
