rootProject.name = "telescope"

include("core")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("benchmarks")

// `examples/` is a pure container for three sibling subprojects — all part of one unified Gradle
// build (no composite-build dance), tasks reachable from telescope root:
//   examples:library             single-JVM smoke-test demos against telescope :core
//   examples:springboot:order-jpa     Spring Boot 4 + JPA + Hibernate + Jackson + Lombok e2e demo,
//                                     wires Mapper<A,B> beans by hand
//   examples:springboot:product-starter  Same stack via `telescope-spring-boot-starter` autoconfig
include("examples:library")
include("examples:springboot:order-jpa")
include("examples:springboot:product-starter")
