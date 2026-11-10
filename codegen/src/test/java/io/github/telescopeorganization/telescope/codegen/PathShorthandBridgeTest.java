package io.github.telescopeorganization.telescope.codegen;

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
    "import static io.github.telescopeorganization.telescope.mapping.Mapping.*;\n" +
    "import io.github.telescopeorganization.telescope.Telescope;\n" +
    "import io.github.telescopeorganization.telescope.annotations.Bridge;\n" +
    "import io.github.telescopeorganization.telescope.conversion.*;\n";

  private static final String STATIC_PATH = "import static io.github.telescopeorganization.telescope.Telescope.path;\n";

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
    ProcessorHarness.source("ps.CartDto", "package ps; public record CartDto(String sku, int count, int total) {}")
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
          " to(io.github.telescopeorganization.telescope.Telescope.path(Order::customer, Outer::address, Inner::zip), Flat::zip)"
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
}
