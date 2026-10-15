package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A navigator is refused when a component or property type cannot be named as a typed constant on
 * its metadata holder. The refusal names why: a wildcard or type variable in the type is the
 * generic case, and a type that is refused with neither, such as one whose type argument names a
 * class that does not exist, has no constant form at all.
 */
class UnemittableTypeRefusalTest {

  private static List<String> refusals(final Processor processor, final String fqcn, final String code) {
    final var compilation = ProcessorHarness.compile(processor, source(fqcn, code));
    assertFalse(compilation.success(), compilation::errorMessages);
    assertFalse(
      compilation
        .generated()
        .keySet()
        .stream()
        .anyMatch(name -> name.startsWith("demo.")),
      () -> "refused navigator still wrote " + compilation.generated().keySet()
    );
    return compilation
      .errors()
      .stream()
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.contains("cannot emit metadata constant"))
      .toList();
  }

  @Test
  @DisplayName("@BeanFocus refuses a property whose type argument names no class, saying it has no constant form")
  void beanPropertyWithNoConstantForm() {
    assertEquals(
      List.of(
        "@BeanFocus: cannot emit metadata constant for property 'items' of type 'java.util.List<Missing>', " +
          "which has no constant form. Remove @BeanFocus from this class to use the runtime path."
      ),
      refusals(
        new BeanFocusProcessor(),
        "demo.Basket",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        import java.util.List;
        @BeanFocus
        public class Basket {
          private List<Missing> items;
          public Basket() {}
          public List<Missing> getItems() { return items; }
          public void setItems(final List<Missing> items) { this.items = items; }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("@BeanFocus refuses a wildcard property type, naming wildcard generics as the reason")
  void beanPropertyWithWildcard() {
    assertEquals(
      List.of(
        "@BeanFocus: cannot emit metadata constant for property 'amounts' of type " +
          "'java.util.List<? extends java.lang.Number>' — generics with wildcard or self-referential bounds " +
          "are not supported. Remove @BeanFocus from this class to use the runtime path."
      ),
      refusals(
        new BeanFocusProcessor(),
        "demo.Ledger",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        import java.util.List;
        @BeanFocus
        public class Ledger {
          private List<? extends Number> amounts;
          public Ledger() {}
          public List<? extends Number> getAmounts() { return amounts; }
          public void setAmounts(final List<? extends Number> amounts) { this.amounts = amounts; }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("@Focus refuses a component whose type argument names no class, saying it has no constant form")
  void recordComponentWithNoConstantForm() {
    assertEquals(
      List.of(
        "@Focus: cannot emit metadata constant for component 'items' of type 'java.util.List<Missing>', " +
          "which has no constant form. Remove @Focus from this record to use the runtime path."
      ),
      refusals(
        new FocusProcessor(),
        "demo.Cart",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        import java.util.List;
        @Focus
        public record Cart(List<Missing> items) {}
        """
      )
    );
  }
}
