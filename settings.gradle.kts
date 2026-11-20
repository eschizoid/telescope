rootProject.name = "telescope"

include("internal")
include("core")
include("jpa")
include("jackson")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("quarkus")
// The Quarkus extension's build steps, and the Quarkus applications its tests boot against them.
include("telescope-quarkus-deployment")
project(":telescope-quarkus-deployment").projectDir = file("quarkus/deployment")
include("telescope-quarkus-tests")
project(":telescope-quarkus-tests").projectDir = file("quarkus/tests")
include("benchmarks")

include("examples:mapstruct-vs-telescope")
include("examples:demo:domain")
include("examples:demo:spring")
include("examples:demo:quarkus")
include("examples:demo:native")
