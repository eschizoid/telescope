package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the compile-time verifier and mapper construction to one answer for a bean whose setter
 * cannot take a value of its property's own type, where the walk reaches the bean through a case of
 * a sealed field paired by a {@code cases} row, or through a property a generic superclass
 * declares: construction refuses the property by name, and the verifier reports that refusal.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, then builds the
 * mapper those classes describe. Every case names its classes with a prefix of its own, because one
 * loader holds them all.
 */
class SetterRefusalAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String BEANS = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Cases.cases;
    import io.github.eschizoid.telescope.Telescope;
    sealed interface @@Shape permits @@Circle, @@Square {}
    record @@Circle(double r) implements @@Shape {}
    record @@Square(double s) implements @@Shape {}
    sealed interface @@ShapeDto permits @@CircleB, @@SquareDto {}
    final class @@CircleB implements @@ShapeDto {
      private double r;
      public @@CircleB() {}
      public double getR() { return r; }
      public void setR(int r) { this.r = r; }
    }
    record @@SquareDto(double s) implements @@ShapeDto {}
    record @@Has(@@Shape shape) {}
    record @@HasDto(@@ShapeDto shape) {}
    record @@In(double r) {}
    class @@InB {
      private double r;
      public @@InB() {}
      public double getR() { return r; }
      public void setR(int r) { this.r = r; }
    }
    class @@GBase<T> {
      private T item;
      public @@GBase() {}
      public T getItem() { return item; }
      public void setItem(T i) { item = i; }
    }
    class @@GSrc extends @@GBase<@@In> { public @@GSrc() {} }
    class @@GDst extends @@GBase<@@InB> { public @@GDst() {} }
    """;

  /**
   * Compiles the beans and a holder whose {@code build()} returns {@code call}, under {@code
   * prefix}, and asserts that construction refuses the mapper with a message the verifier reports.
   */
  private static void assertBothRefuse(final String prefix, final String call) {
    final var code = (BEANS + "class @@Holder { static Object build() { return " + call + "; } }\n").replace(
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
    String refusal = null;
    try {
      holder.getDeclaredMethod("build").invoke(null);
    } catch (final InvocationTargetException e) {
      refusal = e.getCause().getMessage();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    assertNotNull(refusal, "construction built the mapper");
    assertTrue(refusal.contains("cannot be written through it"), refusal);
    assertEquals(List.of(refusal), warnings);
  }

  @Test
  @DisplayName("a bean case of a sealed field, paired by a cases row")
  void sealedCase() {
    assertBothRefuse(
      "SrA",
      "Telescope.mapper(@@Has.class, @@HasDto.class, cases(@@Circle.class, @@CircleB.class), cases(@@Square.class, @@SquareDto.class))"
    );
  }

  @Test
  @DisplayName("a bean case of a sealed field, paired by a cases row, on a mapper that runs forward only")
  void sealedCaseForward() {
    assertBothRefuse(
      "SrB",
      "Telescope.mapperForward(@@Has.class, @@HasDto.class, cases(@@Circle.class, @@CircleB.class), cases(@@Square.class, @@SquareDto.class))"
    );
  }

  @Test
  @DisplayName("a bean reached through a property a generic superclass declares")
  void typeVariableProperty() {
    assertBothRefuse("SrC", "Telescope.mapper(@@GSrc.class, @@GDst.class)");
  }

  @Test
  @DisplayName("a bean reached through a property a generic superclass declares, on a mapper that runs forward only")
  void typeVariablePropertyForward() {
    assertBothRefuse("SrD", "Telescope.mapperForward(@@GSrc.class, @@GDst.class)");
  }
}
