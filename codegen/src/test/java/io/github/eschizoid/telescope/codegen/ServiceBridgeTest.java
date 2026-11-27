package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins how a {@code @Bridge} field whose rows call a service compiles: every operation of the
 * generated bridge takes the service after its own arguments and calls it directly, a bridge
 * reaching that bridge by its pair takes the same service and hands it on, and the field's
 * provider registers the field and its services in place of a class of its own.
 */
class ServiceBridgeTest {

  static final List<JavaFileObject> SHOP = List.of(
    ProcessorHarness.source(
      "sv.Line",
      """
      package sv;
      public class Line {
        private String sku;
        private int price;
        public String getSku() { return sku; }
        public void setSku(final String v) { sku = v; }
        public int getPrice() { return price; }
        public void setPrice(final int v) { price = v; }
      }
      """
    ),
    ProcessorHarness.source(
      "sv.Order",
      """
      package sv;
      import java.util.ArrayList;
      import java.util.List;
      public class Order {
        private String note;
        private List<Line> lines = new ArrayList<>();
        public String getNote() { return note; }
        public void setNote(final String v) { note = v; }
        public List<Line> getLines() { return lines; }
        public void setLines(final List<Line> v) { lines = v; }
      }
      """
    ),
    ProcessorHarness.source("sv.LineChanges", "package sv; public record LineChanges(String sku) {}"),
    ProcessorHarness.source(
      "sv.OrderChanges",
      "package sv; public record OrderChanges(String note, java.util.List<LineChanges> lines) {}"
    ),
    ProcessorHarness.source(
      "sv.Catalog",
      """
      package sv;
      public class Catalog {
        private final int perLetter;
        public Catalog(final int perLetter) { this.perLetter = perLetter; }
        public int price(final LineChanges line) { return line.sku().length() * perLetter; }
      }
      """
    ),
    ProcessorHarness.source(
      "sv.Shop",
      """
      package sv;

      import static io.github.eschizoid.telescope.Telescope.edits;
      import static io.github.eschizoid.telescope.mapping.Mapping.compute;
      import static io.github.eschizoid.telescope.mapping.Mapping.service;

      import io.github.eschizoid.telescope.annotations.Bridge;
      import io.github.eschizoid.telescope.conversion.EditMapper;

      public final class Shop {
        private Shop() {}

        @Bridge
        public static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

        @Bridge
        public static final EditMapper<LineChanges, Line> LINE = edits(
          LineChanges.class,
          Line.class,
          compute(Line::getPrice, service(Catalog::price))
        );
      }
      """
    )
  );

  @Test
  @DisplayName("the bridge of a field whose rows call a service takes it as a parameter and calls it directly")
  void serviceRowCompiles() {
    final var compilation = ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor()),
      List.of(),
      SHOP.toArray(JavaFileObject[]::new)
    );
    final var line = compilation.generated().get("sv.LineChangesToLineBridge");
    final var edits = compilation.generated().get("sv.OrderChangesToOrderBridge");
    final var provider = compilation.generated().get("sv.LineChangesToLineBridgeProvider");
    assertTrue(compilation.success(), () -> compilation.diagnostics() + "\n" + line + "\n" + edits + "\n" + provider);
    assertTrue(line.contains("__svc_sv_Catalog.price(s)"), line);
    assertTrue(line.contains("forward(final sv.LineChanges s, final sv.Catalog __svc_sv_Catalog)"), line);
    assertTrue(edits.contains("final sv.Catalog __svc_sv_Catalog"), edits);
    assertTrue(provider.contains("return List.of(sv.Catalog.class);"), provider);
    assertTrue(!provider.contains("FieldMapper"), provider);
  }

  /** {@code SHOP} with a class calling {@code with(...)} on its fields with {@code arguments}. */
  private static List<JavaFileObject> binding(final String field, final String arguments) {
    final var sources = new ArrayList<>(SHOP);
    sources.add(
      ProcessorHarness.source(
        "sv.Binds",
        "package sv;\npublic final class Binds {\n  static Object bound() {\n    return Shop." +
          field +
          ".with(" +
          arguments +
          ");\n  }\n}\n"
      )
    );
    sources.add(
      ProcessorHarness.source("sv.Clock", "package sv;\npublic class Clock {}\n")
    );
    return sources;
  }

  private static List<String> errors(final List<JavaFileObject> sources) {
    final var compilation = ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
  }

  @Test
  @DisplayName("the verifier refuses a with(...) on a @Bridge field handed a value no service of it accepts")
  void verifierRefusesAnUnneededService() {
    assertEquals(
      List.of("Shop.LINE.with(...) was handed a Clock, which no row it converts through calls; it calls Catalog."),
      errors(binding("LINE", "new Catalog(1), new Clock()"))
    );
  }

  @Test
  @DisplayName("the verifier refuses a with(...) on a @Bridge field missing a service a field it converts through calls")
  void verifierRefusesAMissingNestedService() {
    final var refused = errors(binding("EDITS", ""));
    assertEquals(1, refused.size(), refused::toString);
    assertTrue(refused.getFirst().startsWith("Shop.EDITS.with(...) was handed no Catalog"), refused::toString);
  }

  @Test
  @DisplayName("the verifier refuses two values for one service, and accepts one value per service")
  void verifierRefusesARepeatedService() {
    assertEquals(
      List.of("Shop.LINE.with(...) was handed two values for its Catalog, a Catalog and a Catalog."),
      errors(binding("LINE", "new Catalog(1), new Catalog(2)"))
    );
    assertEquals(List.of(), errors(binding("EDITS", "new Catalog(1)")));
  }
}
