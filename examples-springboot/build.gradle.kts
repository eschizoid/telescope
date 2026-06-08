// Parent build for the examples-springboot demo project. Two submodules — order-jpa and
// product-starter — each apply Spring Boot, Spring dependency-management, Java, and spotless
// independently because Spring Boot's plugin doesn't compose cleanly across subprojects.
// This root carries no Java sources of its own.

plugins {
    // Spotless declared at the root for the kotlin-gradle DSL formatting only; per-submodule
    // Java spotless lives in each submodule's build.gradle.kts so the toolchain init order
    // matches the Spring Boot plugin order Spring expects.
    id("com.diffplug.spotless") version "7.0.0"
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
