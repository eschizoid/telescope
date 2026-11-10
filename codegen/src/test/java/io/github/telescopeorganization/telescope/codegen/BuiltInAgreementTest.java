package io.github.telescopeorganization.telescope.codegen;

import static io.github.telescopeorganization.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.telescopeorganization.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the compile-time verifier and mapper construction to one answer for the conversions the
 * shared spec builds in: a pair both convert is one the verifier stays silent on and construction
 * builds, and a pair construction refuses is one the verifier reports in the same words.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, then builds the
 * mapper those classes describe. Every case names its classes with a prefix of its own, because one
 * loader holds them all.
 */
class BuiltInAgreementTest {

  private static final String PACKAGE = "io.github.telescopeorganization.telescope.codegen";

  private static final String TYPES = """
    package io.github.telescopeorganization.telescope.codegen;
    import static io.github.telescopeorganization.telescope.mapping.Mapping.*;
    import io.github.telescopeorganization.telescope.Telescope;
    enum @@Status { OPEN, CLOSED }
    record @@Text(String id, String status, String paid, java.util.List<String> tags) {}
    record @@Values(long id, @@Status status, boolean paid, java.util.Set<Long> tags) {}
    record @@Narrow(int n, java.util.List<Short> xs) {}
    record @@Wide(long n, java.util.List<Integer> xs) {}
    record @@Stamp(String at) {}
    record @@StampDto(java.time.Instant at) {}
    record @@Code(String code) {}
    record @@CodeDto(int number) {}
    record @@Outer(@@Narrow inner) {}
    record @@OuterDto(@@Wide inner) {}
    """;

  /**
   * The verifier's warnings and construction's refusal, or null, for {@code call} under {@code
   * prefix}.
   */
  private record Outcome(List<String> warnings, String refusal) {}

  private static Outcome outcome(final String prefix, final String call) {
    final var code = (TYPES + "class @@Holder { static Object build() { return " + call + "; } }\n").replace(
      "@@",
      prefix
    );
    final var compilation = compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn"),
      source(PACKAGE + "." + prefix + "Holder", code)
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      holder.getDeclaredMethod("build").invoke(null);
      return new Outcome(warnings, null);
    } catch (final InvocationTargetException e) {
      return new Outcome(warnings, e.getCause().getMessage());
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertBothConvert(final String prefix, final String call) {
    final var outcome = outcome(prefix, call);
    assertNull(outcome.refusal(), outcome::refusal);
    assertEquals(List.of(), outcome.warnings());
  }

  private static void assertBothRefuse(final String prefix, final String call, final String words) {
    final var outcome = outcome(prefix, call);
    assertNotNull(outcome.refusal(), "construction built the mapper");
    assertTrue(outcome.refusal().contains(words), outcome::refusal);
    // Construction refuses at the first pair it cannot convert, and the verifier reports every one.
    assertEquals(outcome.refusal(), outcome.warnings().getFirst(), () -> outcome.warnings().toString());
  }

  @Test
  @DisplayName("text into a number, an enum, a boolean and a set of numbers, both ways")
  void textConvertsBothWays() {
    assertBothConvert("BaA", "Telescope.mapper(@@Text.class, @@Values.class)");
  }

  @Test
  @DisplayName("a plain to row between two names of a number and text")
  void aPlainRowConverts() {
    assertBothConvert("BaB", "Telescope.mapper(@@Code.class, @@CodeDto.class, to(@@Code::code, @@CodeDto::number))");
  }

  @Test
  @DisplayName("a widening, and one below a nested pair, on a mapper that runs forward only")
  void wideningForwardOnly() {
    assertBothConvert("BaC", "Telescope.mapperForward(@@Narrow.class, @@Wide.class)");
    assertBothConvert("BaD", "Telescope.mapperForward(@@Outer.class, @@OuterDto.class)");
  }

  @Test
  @DisplayName("a widening on a mapper that converts both ways")
  void wideningBothWays() {
    assertBothRefuse("BaE", "Telescope.mapper(@@Narrow.class, @@Wide.class)", "which converts forward only");
  }

  @Test
  @DisplayName("a widening below a nested pair on a mapper that converts both ways")
  void nestedWideningBothWays() {
    assertBothRefuse("BaF", "Telescope.mapper(@@Outer.class, @@OuterDto.class)", "which converts forward only");
  }

  @Test
  @DisplayName("a narrowing on a mapper that runs forward only")
  void narrowing() {
    assertBothRefuse("BaG", "Telescope.mapperForward(@@Wide.class, @@Narrow.class)", "would narrow long to int");
  }

  @Test
  @DisplayName("text against a date")
  void textAgainstADate() {
    assertBothRefuse("BaH", "Telescope.mapper(@@Stamp.class, @@StampDto.class)", "only in a format");
  }
}
