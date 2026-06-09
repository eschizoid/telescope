rootProject.name = "telescope"

include("core")
include("codegen")
include("lombok")
include("spring-boot-starter")
include("benchmarks")

include("examples:library")
include("examples:springboot:order-jpa")
include("examples:springboot:product-starter")
include("examples:springboot:invoicing")
