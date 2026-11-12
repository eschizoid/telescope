rootProject.name = "telescope"

include("internal")
include("core")
include("jpa")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("quarkus")
include("benchmarks")

include("examples:mapstruct-vs-telescope")
include("examples:demo:domain")
include("examples:demo:spring")
include("examples:demo:quarkus")
include("examples:demo:native")
