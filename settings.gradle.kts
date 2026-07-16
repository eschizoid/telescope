rootProject.name = "telescope"

include("internal")
include("core")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("quarkus")
include("benchmarks")
include("native-smoke")

include("examples:library")
include("examples:mapstruct-vs-telescope")
include("examples:graphql")
include("examples:springboot:order-jpa")
include("examples:springboot:product-starter")
include("examples:springboot:invoicing")
include("examples:springboot:org-chart")
