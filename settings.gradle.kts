rootProject.name = "telescope"

include("core")
include("codegen")
include("lombok")
include("benchmarks")
// `:examples` temporarily excluded — its addition since v0.3.0 is the suspected trigger for
// axion-release's auto-increment going silent on this repo (releases tried to recreate v0.3.0).
// Re-add once the release pipeline is verified working at v0.4.0+.
// include("examples")
