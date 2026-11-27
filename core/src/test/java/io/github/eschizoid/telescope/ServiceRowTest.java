package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.service;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElseGet;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the rows that call a service on the runtime path: an unbound mapper refuses every operation
 * before it writes anything, naming its field and the row; {@code with} binds a copy and refuses a
 * null, an unneeded value, two values for one service and a missing one; and each service form
 * gives what its plain row gives with the service's answer.
 */
class ServiceRowTest {

  public static class Line {

    private String sku;
    private int price;

    public Line() {}

    public String getSku() {
      return sku;
    }

    public void setSku(final String sku) {
      this.sku = sku;
    }

    public int getPrice() {
      return price;
    }

    public void setPrice(final int price) {
      this.price = price;
    }
  }

  public static class Order {

    private String note;
    private List<Line> lines = new ArrayList<>();

    public Order() {}

    public String getNote() {
      return note;
    }

    public void setNote(final String note) {
      this.note = note;
    }

    public List<Line> getLines() {
      return lines;
    }

    public void setLines(final List<Line> lines) {
      this.lines = lines;
    }
  }

  public record LineChanges(String sku) {}

  public record OrderChanges(String note, List<LineChanges> lines) {}

  /** Prices a line from its own table, as a bean over a repository would. */
  public static class Catalog {

    private final int perLetter;

    public Catalog(final int perLetter) {
      this.perLetter = perLetter;
    }

    public int price(final LineChanges line) {
      return line.sku().length() * perLetter;
    }
  }

  /** A second service, which no row of these mappers calls. */
  public static final class Clock {

    public String now() {
      return "noon";
    }
  }

  static final class Shop {

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

    static final EditMapper<LineChanges, Line> LINE = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, service(Catalog::price))
    );

    static final EditMapper<OrderChanges, Order> NAMED = edits(
      OrderChanges.class,
      Order.class,
      via(OrderChanges::lines, Order::getLines, LINE)
    );
  }

  private static Order held() {
    final var order = new Order();
    order.setNote("kept");
    return order;
  }

  @Test
  @DisplayName("an unbound edit mapper refuses a patch naming its field, the row and the mapper the row is in")
  void unboundRefusesNamingTheRow() {
    final var order = held();
    final var refused = assertThrows(IllegalStateException.class, () ->
      Shop.EDITS.patchInto(order, new OrderChanges("new", List.of(new LineChanges("ab"))))
    );
    assertEquals(
      "Shop.EDITS needs a Catalog for compute(Line::getPrice, service(Catalog::price)) in Shop.LINE, and none is" +
        " bound. Inject the EditMapper<OrderChanges, Order> bean, or bind one with Shop.EDITS.with(catalog).",
      refused.getMessage()
    );
    assertEquals("kept", order.getNote());
    assertTrue(order.getLines().isEmpty());
  }

  @Test
  @DisplayName("a bound edit mapper prices each line through its service and leaves the field unbound")
  void boundPricesEachLine() {
    final var bound = Shop.EDITS.with(new Catalog(10));
    assertNotSame(Shop.EDITS, bound);
    final var order = held();
    bound.patchInto(order, new OrderChanges(null, List.of(new LineChanges("abc"), new LineChanges("x"))));
    assertEquals("kept", order.getNote());
    assertEquals(List.of(30, 10), order.getLines().stream().map(Line::getPrice).toList());
    assertEquals(List.of(Catalog.class), Shop.EDITS.services());
    assertEquals(List.of(Catalog.class), bound.services());
    assertThrows(IllegalStateException.class, () -> Shop.EDITS.forward(new OrderChanges("n", List.of())));
  }

  @Test
  @DisplayName("a mapper a via row names brings its services to the mapper naming it")
  void viaBringsItsServices() {
    assertEquals(List.of(Catalog.class), Shop.NAMED.services());
    final var order = Shop.NAMED.with(new Catalog(2)).forward(new OrderChanges("n", List.of(new LineChanges("abcd"))));
    assertEquals(8, order.getLines().getFirst().getPrice());
  }

  @Test
  @DisplayName("with refuses a null, an unneeded value, two values for one service and a missing one, by name")
  void withRefusesByName() {
    assertEquals(
      "Shop.LINE.with(...) was handed null as its service 1; hand it the Catalog its rows call.",
      assertThrows(IllegalArgumentException.class, () -> Shop.LINE.with((Object) null)).getMessage()
    );
    assertEquals(
      "Shop.LINE.with(...) was handed a Clock, which no row it converts through calls; it calls Catalog.",
      assertThrows(IllegalArgumentException.class, () -> Shop.LINE.with(new Catalog(1), new Clock())).getMessage()
    );
    assertEquals(
      "Shop.LINE.with(...) was handed two values for its Catalog, a Catalog and a Catalog.",
      assertThrows(IllegalArgumentException.class, () -> Shop.LINE.with(new Catalog(1), new Catalog(2))).getMessage()
    );
    assertEquals(
      "Shop.LINE.with(...) was handed no Catalog, which compute(Line::getPrice, service(Catalog::price)) calls.",
      assertThrows(IllegalArgumentException.class, Shop.LINE::with).getMessage()
    );
  }

  public record Stamp(String sku, String code, int size) {}

  public record Stamped(String sku, String label, String tag, String code, int size, boolean promoted) {}

  /** Answers each service form. */
  public static final class Labels {

    public String label() {
      return "labelled";
    }

    public String code(final String sku) {
      return "C-" + sku;
    }

    public Boolean promotes(final Stamp stamp) {
      return stamp.size() > 2;
    }

    public String fallback() {
      return "none";
    }
  }

  @Test
  @DisplayName("every service form gives what its plain row gives with the service's answer")
  void everyForm() {
    final ForwardMapper<Stamp, Stamped> stamps = Telescope.mapperForward(
      Stamp.class,
      Stamped.class,
      compute(Stamped::label, service(Labels::label)),
      toOneWay(Stamp::sku, Stamped::tag, service(Labels::code)),
      toOrElseGet(Stamp::code, Stamped::code, service(Labels::fallback)),
      when(service(Labels::promotes), constant(Stamped::promoted, true))
    );
    assertEquals(List.of(Labels.class), stamps.services());
    final var bound = stamps.with(new Labels());
    assertEquals(new Stamped(null, "labelled", "C-s", "none", 3, true), bound.forward(new Stamp("s", null, 3)));
    assertEquals(new Stamped(null, "labelled", "C-s", "x", 1, false), bound.forward(new Stamp("s", "x", 1)));
  }

  @Test
  @DisplayName("Telescope.map refuses a row calling a service, since a Telescope has nothing to bind it to")
  void mapRefuses() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.map(LineChanges.class, Line.class, compute(Line::getPrice, service(Catalog::price)))
    );
    assertTrue(refused.getMessage().startsWith("Telescope.map returns a Telescope"), refused::getMessage);
  }
}
