rootProject.name = "telescope"

include("core")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("benchmarks")

// `examples/` is a pure container for two siblings:
//   examples/library/   — single-JVM smoke-test demos against telescope :core (Gradle subproject)
//   examples/springboot/ — Spring Boot composite build (its own settings.gradle.kts, not included
//                          here because Spring Boot's plugin classpath doesn't compose with the
//                          parent telescope build).
include("examples:library")
