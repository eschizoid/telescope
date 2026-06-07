package io.github.eschizoid.telescope.examples;

/**
 * Entry point for the {@code telescope-examples} smoke suite. Each demo runs in sequence and prints
 * a small "Before / After" trace for every operation it exercises. If any demo throws, the build
 * fails — this is intentional: the examples module IS the smoke test (no JUnit, no fixtures, just
 * real call sites with real data).
 *
 * <p>The goal is for {@code ./gradlew :examples:run} to be the first downstream consumer of the
 * library, so a public-API regression trips a compile error in this module long before it would
 * surface to a real user. See PLAN.md section 3 ("the unified factory in anger") for the rationale.
 */
public final class Main {

  private Main() {}

  public static void main(final String[] args) {
    section("Runtime navigation — Telescope.of / Telescope.ofBean / fieldByName");
    RuntimeNavigationDemo.run();

    section("Typed container navigation — list / setField / mapField / optional");
    ContainerNavigationDemo.run();

    section("Sealed-type narrowing and filter");
    SealedAndFilterDemo.run();

    section("Multi-edit — Telescope.all(over(...))");
    MultiEditDemo.run();

    section("Indexed traversal — withIndex / updateIndexed / toListIndexed");
    IndexedDemo.run();

    section("Effectful update — updateAsync / updateOptional / updateEither / updateValidated");
    EffectfulUpdateDemo.run();

    section("Conversion — from/to/using + asTelescope composition");
    ConversionDemo.run();

    section("Deep mapping — Telescope.map / Telescope.mapper / writeBean");
    DeepMappingDemo.run();

    section("Codegen — @Focus / @BeanFocus / @Bridge");
    CodegenDemo.run();

    section("Lombok integration — @Data + @Builder");
    LombokDemo.run();

    section("All demos completed");
    System.out.println("If you see this line, every public entry point survived the smoke test.");
  }

  private static void section(final String title) {
    System.out.println();
    System.out.println("=== " + title + " ===");
  }
}
