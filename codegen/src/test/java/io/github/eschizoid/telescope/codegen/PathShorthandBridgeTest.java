package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A {@code @Bridge} field whose rows write a path as {@code Telescope.path(A::b, B::c, ...)}
 * generates, byte for byte, what the same rows written as the {@code of(A.class).field(...)} chain
 * generate, with the same diagnostics, whether {@code path} is qualified or imported statically.
 */
class PathShorthandBridgeTest {

  private static final String HEADER =
    "package ps;\n" +
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n";

  private static final String STATIC_PATH = "import static io.github.eschizoid.telescope.Telescope.path;\n";

  private static final List<JavaFileObject> MODEL = List.of(
    ProcessorHarness.source("ps.Inner", "package ps; public record Inner(String city, int zip) {}"),
    ProcessorHarness.source("ps.Outer", "package ps; public record Outer(Inner address, String label) {}"),
    ProcessorHarness.source("ps.Order", "package ps; public record Order(Outer customer, int qty) {}"),
    ProcessorHarness.source(
      "ps.Flat",
      "package ps; public record Flat(String city, String label, int zip, int qty) {}"
    ),
    ProcessorHarness.source(
      "ps.Line",
      "package ps; public class Line {\n" +
        "  private String sku;\n  private int count;\n" +
        "  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
        "  public int getCount() { return count; }\n  public void setCount(final int v) { count = v; }\n}\n"
    ),
    ProcessorHarness.source(
      "ps.Cart",
      "package ps; public class Cart {\n" +
        "  private Line line;\n  private int total;\n" +
        "  public Line getLine() { return line; }\n  public void setLine(final Line v) { line = v; }\n" +
        "  public int getTotal() { return total; }\n  public void setTotal(final int v) { total = v; }\n}\n"
    ),
    ProcessorHarness.source("ps.CartDto", "package ps; public record CartDto(String sku, int count, int total) {}"),
    ProcessorHarness.source("ps.Spot", "package ps; public record Spot(String city, int zip) {}"),
    ProcessorHarness.source("ps.Ship", "package ps; public record Ship(Spot place, String tag) {}"),
    ProcessorHarness.source("ps.Shipment", "package ps; public record Shipment(Ship ship, int qty) {}")
  );

  /**
   * A compilation of the model and a holder declaring one {@code @Bridge} field over {@code rows}.
   */
  private static ProcessorHarness.Compilation compiled(final String imports, final String pair, final String rows) {
    final var sources = new ArrayList<>(MODEL);
    sources.add(
      ProcessorHarness.source(
        "ps.Mappers",
        HEADER +
          imports +
          "public final class Mappers {\n" +
          "  @Bridge public static final Mapper<" +
          pair.replace(".class", "") +
          "> MAPPER = Telescope.mapper(" +
          pair +
          ", " +
          rows +
          ");\n}\n"
      )
    );
    return ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn"),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  private static List<String> messages(final ProcessorHarness.Compilation compilation) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() != Diagnostic.Kind.NOTE)
      .map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
      .toList();
  }

  static Stream<Arguments> forms() {
    final var city3 = "Telescope.of(Order.class).field(Order::customer).field(Outer::address).field(Inner::city)";
    final var zip3 = "Telescope.of(Order.class).field(Order::customer).field(Outer::address).field(Inner::zip)";
    final var label2 = "Telescope.of(Order.class).field(Order::customer).field(Outer::label)";
    return Stream.of(
      Arguments.of(
        "a source path of two steps and two of three, qualified",
        "",
        "Order.class, Flat.class",
        "to(" + label2 + ", Flat::label), to(" + city3 + ", Flat::city), to(" + zip3 + ", Flat::zip)",
        "to(Telescope.path(Order::customer, Outer::label), Flat::label)," +
          " to(Telescope.path(Order::customer, Outer::address, Inner::city), Flat::city)," +
          " to(Telescope.path(Order::customer, Outer::address, Inner::zip), Flat::zip)"
      ),
      Arguments.of(
        "a source path imported statically",
        STATIC_PATH,
        "Order.class, Flat.class",
        "to(" + label2 + ", Flat::label), to(" + city3 + ", Flat::city), to(" + zip3 + ", Flat::zip)",
        "to(path(Order::customer, Outer::label), Flat::label)," +
          " to(path(Order::customer, Outer::address, Inner::city), Flat::city)," +
          " to(path(Order::customer, Outer::address, Inner::zip), Flat::zip)"
      ),
      Arguments.of(
        "a source path continued by a field step",
        "",
        "Order.class, Flat.class",
        "to(" + label2 + ", Flat::label), to(" + city3 + ", Flat::city), to(" + zip3 + ", Flat::zip)",
        "to(Telescope.path(Order::customer, Outer::label), Flat::label)," +
          " to(Telescope.path(Order::customer, Outer::address).field(Inner::city), Flat::city)," +
          " to(io.github.eschizoid.telescope.Telescope.path(Order::customer, Outer::address, Inner::zip), Flat::zip)"
      ),
      Arguments.of(
        "a target path of two steps and two of three",
        STATIC_PATH,
        "Flat.class, Order.class",
        "to(Flat::label, Telescope.of(Order.class).field(Order::customer).field(Outer::label))," +
          " to(Flat::city, Telescope.of(Order.class).field(Order::customer).field(Outer::address).field(Inner::city))," +
          " to(Flat::zip, Telescope.of(Order.class).field(Order::customer).field(Outer::address).field(Inner::zip))",
        "to(Flat::label, path(Order::customer, Outer::label))," +
          " to(Flat::city, path(Order::customer, Outer::address, Inner::city))," +
          " to(Flat::zip, Telescope.path(Order::customer, Outer::address, Inner::zip))"
      ),
      Arguments.of(
        "a source path through beans, which the chain starts with ofBean",
        "",
        "Cart.class, CartDto.class",
        "to(Telescope.ofBean(Cart.class).field(Cart::getLine).field(Line::getSku), CartDto::sku)," +
          " to(Telescope.ofBean(Cart.class).field(Cart::getLine).field(Line::getCount), CartDto::count)",
        "to(Telescope.path(Cart::getLine, Line::getSku), CartDto::sku)," +
          " to(Telescope.path(Cart::getLine, Line::getCount), CartDto::count)"
      ),
      Arguments.of(
        "a path on each side, of three steps",
        STATIC_PATH,
        "Order.class, Shipment.class",
        "to(" +
          city3 +
          ", Telescope.of(Shipment.class).field(Shipment::ship).field(Ship::place).field(Spot::city))," +
          " to(" +
          zip3 +
          ", Telescope.of(Shipment.class).field(Shipment::ship).field(Ship::place).field(Spot::zip))",
        "to(path(Order::customer, Outer::address, Inner::city), path(Shipment::ship, Ship::place, Spot::city))," +
          " to(Telescope.path(Order::customer, Outer::address, Inner::zip), path(Shipment::ship, Ship::place, Spot::zip))"
      ),
      Arguments.of(
        "a target path through beans",
        STATIC_PATH,
        "CartDto.class, Cart.class",
        "to(CartDto::sku, Telescope.ofBean(Cart.class).field(Cart::getLine).field(Line::getSku))," +
          " to(CartDto::count, Telescope.ofBean(Cart.class).field(Cart::getLine).field(Line::getCount))",
        "to(CartDto::sku, path(Cart::getLine, Line::getSku)), to(CartDto::count, path(Cart::getLine, Line::getCount))"
      )
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forms")
  @DisplayName("a path(...) row generates exactly what the field chain it stands for generates")
  void generatesWhatTheChainGenerates(
    final String name,
    final String imports,
    final String pair,
    final String chainRows,
    final String pathRows
  ) {
    final var chain = compiled("", pair, chainRows);
    final var shorthand = compiled(imports, pair, pathRows);
    assertTrue(chain.success(), chain::errorMessages);
    assertTrue(shorthand.success(), shorthand::errorMessages);
    final var bridge = "ps." + pair.replace(".class", "").replace(", ", "To") + "Bridge";
    assertTrue(chain.generated().containsKey(bridge), () -> chain.generated().keySet().toString());
    assertEquals(chain.generated().keySet(), shorthand.generated().keySet());
    for (final var generated : chain.generated().entrySet()) {
      assertEquals(generated.getValue(), shorthand.generated().get(generated.getKey()), generated.getKey());
    }
    assertEquals(chain.resources(), shorthand.resources());
    assertEquals(messages(chain), messages(shorthand));
  }

  /**
   * The model with each record a navigator is generated for, and a holder of one field over {@code
   * rows}.
   */
  private static ProcessorHarness.Compilation navigated(final String pair, final String rows) {
    final var focus = "@io.github.eschizoid.telescope.annotations.Focus ";
    final var sources = new ArrayList<JavaFileObject>();
    sources.add(
      ProcessorHarness.source("ps.Inner", "package ps; " + focus + "public record Inner(String city, int zip) {}")
    );
    sources.add(
      ProcessorHarness.source(
        "ps.Outer",
        "package ps; " + focus + "public record Outer(Inner address, String label) {}"
      )
    );
    sources.add(
      ProcessorHarness.source("ps.Order", "package ps; " + focus + "public record Order(Outer customer, int qty) {}")
    );
    sources.add(
      ProcessorHarness.source("ps.Cart", "package ps; " + focus + "public record Cart(java.util.List<Inner> items) {}")
    );
    sources.add(
      ProcessorHarness.source(
        "ps.Other",
        "package ps; " + focus + "public record Other(Outer customer, String note) {}"
      )
    );
    sources.add(
      ProcessorHarness.source(
        "ps.Flat",
        "package ps; public record Flat(String city, String label, int zip, int qty) {}"
      )
    );
    sources.add(
      ProcessorHarness.source(
        "ps.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final Mapper<" +
          pair.replace(".class", "") +
          "> MAPPER = Telescope.mapper(" +
          pair +
          ", " +
          rows +
          ");\n}\n"
      )
    );
    return ProcessorHarness.compileFully(
      List.<Processor>of(new FocusProcessor(), new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn"),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  @Test
  @DisplayName("a generated navigator in a row generates exactly what the path(...) it stands for generates")
  void aNavigatorGeneratesWhatThePathGenerates() {
    final var navigator = navigated(
      "Order.class, Flat.class",
      "to(OrderTelescope.of().customer().label(), Flat::label)," +
        " to(OrderTelescope.of().customer().address().city(), Flat::city)," +
        " to(OrderTelescope.of().customer().address().zip(), Flat::zip)"
    );
    final var shorthand = navigated(
      "Order.class, Flat.class",
      "to(Telescope.path(Order::customer, Outer::label), Flat::label)," +
        " to(Telescope.path(Order::customer, Outer::address, Inner::city), Flat::city)," +
        " to(Telescope.path(Order::customer, Outer::address, Inner::zip), Flat::zip)"
    );
    assertTrue(navigator.success(), navigator::errorMessages);
    assertTrue(shorthand.success(), shorthand::errorMessages);
    for (final var name : List.of("ps.OrderToFlatBridge", "ps.OrderToFlatBridgeProvider")) {
      assertTrue(shorthand.generated().containsKey(name), () -> shorthand.generated().keySet().toString());
      assertEquals(shorthand.generated().get(name), navigator.generated().get(name), name);
    }
    assertEquals(messages(shorthand), messages(navigator));
  }

  @Test
  @DisplayName("a navigator of another class is refused on the row, though its steps name properties the source has")
  void aNavigatorOfAnotherClassIsRefused() {
    // Other and Order both have a customer of type Outer, so read by name alone the steps would
    // read Order's own property; the runtime refuses the row, as a path rooted elsewhere.
    final var refused = navigated("Order.class, Flat.class", "to(OtherTelescope.of().customer().label(), Flat::label)");
    assertTrue(
      refused.hasError("this path starts at Other, which is not Order or a class it extends; start it at Order"),
      refused::errorMessages
    );
  }

  @Test
  @DisplayName("a navigator's container step, which reads no property, is refused by name as in a path")
  void aNavigatorStepReadingNoPropertyIsRefused() {
    final var refused = navigated("Cart.class, Flat.class", "to(CartTelescope.of().items().each().city(), Flat::city)");
    assertTrue(refused.hasError("each(...) in a path has no generated form"), refused::errorMessages);
  }

  @Test
  @DisplayName("a row with a path on each side compiles with nothing for the verifier to report")
  void aPathOnEachSideLeavesTheVerifierSilent() {
    final var compiled = compiled(
      STATIC_PATH,
      "Order.class, Shipment.class",
      "to(path(Order::customer, Outer::address, Inner::city), path(Shipment::ship, Ship::place, Spot::city))"
    );
    assertTrue(compiled.success(), compiled::errorMessages);
    assertTrue(compiled.generated().containsKey("ps.OrderToShipmentBridge"), () ->
      compiled.generated().keySet().toString()
    );
    assertEquals(List.of(), messages(compiled));
  }
}
