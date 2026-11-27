package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that an edit mapper converts the elements of a collection it writes by name through the edit
 * mapper its class declares for the element pair, with that mapper's rows, whichever of the two
 * fields is declared first; that a {@code via} row naming another mapper wins; and that two fields
 * for one element pair are refused by name.
 */
class ElementEditMapperTest {

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

  /** A line whose own record declares the edit mapper of its pair. */
  public record PartChanges(String sku) {
    static final EditMapper<PartChanges, Line> PART = Telescope.edits(
      PartChanges.class,
      Line.class,
      compute(Line::getPrice, PartChanges::price)
    );

    static int price(final PartChanges part) {
      return part.sku().length() * 100;
    }
  }

  public record PartOrder(String note, List<PartChanges> lines) {}

  /** A line whose edit mapper is declared in a class that is neither the owner's nor the pair's. */
  public record BoxChanges(String sku) {}

  public record BoxOrder(String note, List<BoxChanges> lines) {}

  static final class BoxPrices {

    static final EditMapper<BoxChanges, Line> BOX = Telescope.edits(
      BoxChanges.class,
      Line.class,
      compute(Line::getPrice, BoxPrices::of)
    );

    static int of(final BoxChanges box) {
      return 7;
    }
  }

  /** Prices a line by the length of its SKU, and another way for the mapper a via row names. */
  public static final class Prices {

    private Prices() {}

    public static int of(final LineChanges line) {
      return line.sku().length() * 10;
    }

    public static int discounted(final LineChanges line) {
      return 1;
    }
  }

  /** The owner's mapper first, the element's after it. */
  static final class OwnerFirst {

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

    static final EditMapper<LineChanges, Line> LINE = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::of)
    );
  }

  /** The element's mapper first, the owner's after it. */
  static final class LineFirst {

    static final EditMapper<LineChanges, Line> LINE = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::of)
    );

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);
  }

  /** A via row naming another element mapper than the one the class declares. */
  static final class Named {

    private static final EditMapper<LineChanges, Line> DISCOUNTED = Telescope.edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::discounted)
    );

    static final EditMapper<OrderChanges, Order> EDITS = edits(
      OrderChanges.class,
      Order.class,
      via(OrderChanges::lines, Order::getLines, DISCOUNTED)
    );
  }

  /** Two edit mappers for one element pair. */
  static final class Twice {

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

    static final EditMapper<LineChanges, Line> LINE = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::of)
    );

    static final EditMapper<LineChanges, Line> OTHER = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::discounted)
    );
  }

  /** The owner's mapper, whose line pair has its edit mapper on the line's own record. */
  static final class OnTheRecord {

    static final EditMapper<PartOrder, Order> EDITS = edits(PartOrder.class, Order.class);
  }

  /** The owner's mapper, whose line pair's edit mapper is declared where no edit mapper looks. */
  static final class Unreached {

    static final EditMapper<BoxOrder, Order> EDITS = edits(BoxOrder.class, Order.class);
  }

  /** The class's element mapper is another class's mapper, assigned to a field of its own. */
  static final class Aliased {

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

    static final EditMapper<LineChanges, Line> LINE = SharedLines.LINE;
  }

  /** Holds the line mapper {@link Aliased} names, built by a helper. */
  static final class SharedLines {

    static final EditMapper<LineChanges, Line> LINE = line();

    private static EditMapper<LineChanges, Line> line() {
      return edits(LineChanges.class, Line.class, compute(Line::getPrice, Prices::of));
    }
  }

  /** A mapper of the class used by its static initialiser before the element's mapper is built. */
  static final class UsedEarly {

    static final EditMapper<OrderChanges, Order> EDITS = edits(OrderChanges.class, Order.class);

    static final Order EARLY = EDITS.forward(new OrderChanges("n", List.of(new LineChanges("ab"))));

    static final EditMapper<LineChanges, Line> LINE = edits(
      LineChanges.class,
      Line.class,
      compute(Line::getPrice, Prices::of)
    );
  }

  private static Order order() {
    final var order = new Order();
    order.setNote("held");
    final var line = new Line();
    line.setSku("old");
    order.getLines().add(line);
    return order;
  }

  @Test
  @DisplayName("the elements go through the class's element mapper, declared after the owner's or before it")
  void convertsElementsThroughTheDeclaredMapper() {
    for (final var mapper : List.of(OwnerFirst.EDITS, LineFirst.EDITS)) {
      final var order = order();
      final var lines = order.getLines();
      mapper.patchInto(order, new OrderChanges(null, List.of(new LineChanges("abcd"))));
      assertSame(lines, order.getLines(), "the held list keeps its identity");
      assertEquals(40, order.getLines().getFirst().getPrice(), "the element mapper's compute row ran");
      assertEquals("held", order.getNote());
      assertEquals(
        30,
        mapper.forward(new OrderChanges("n", List.of(new LineChanges("xyz")))).getLines().getFirst().getPrice()
      );
    }
  }

  @Test
  @DisplayName("explain names the element mapper a property converts through")
  void explainsTheElementMapper() {
    final var report = OwnerFirst.EDITS.explain();
    assertTrue(
      report.nodes().contains(new OpticNode.ElementsThrough(Order.class, "lines", "OwnerFirst.LINE")),
      report::toString
    );
    assertTrue(report.toString().contains("Order.lines: elements through OwnerFirst.LINE"), report::toString);
  }

  @Test
  @DisplayName("a via row naming another element mapper wins over the one the class declares")
  void anExplicitViaWins() {
    final var order = order();
    Named.EDITS.patchInto(order, new OrderChanges(null, List.of(new LineChanges("abcd"))));
    assertEquals(1, order.getLines().getFirst().getPrice());
  }

  @Test
  @DisplayName("two edit mappers for one element pair are refused by name")
  void refusesTwoElementMappers() {
    final var refused = assertThrows(ExceptionInInitializerError.class, () -> Twice.EDITS.forward(null));
    assertEquals(
      PairingMessages.editElementMappersAmbiguous(
        "OrderChanges",
        "Order",
        "lines",
        List.of("Twice.LINE", "Twice.OTHER")
      ),
      refused.getCause().getMessage()
    );
  }

  @Test
  @DisplayName("an element mapper used while its class is still initialising, before it is built, is named")
  void refusesAnElementMapperNotBuiltYet() {
    final var refused = assertThrows(ExceptionInInitializerError.class, () -> UsedEarly.EDITS.forward(null));
    assertEquals(PairingMessages.editElementMapperNotBuilt("UsedEarly.LINE"), refused.getCause().getMessage());
  }

  @Test
  @DisplayName("the elements go through an edit mapper the element's own class declares")
  void convertsElementsThroughTheElementClassMapper() {
    final var order = order();
    OnTheRecord.EDITS.patchInto(order, new PartOrder(null, List.of(new PartChanges("abc"))));
    assertEquals(300, order.getLines().getFirst().getPrice());
    assertTrue(
      OnTheRecord.EDITS.explain()
        .nodes()
        .contains(new OpticNode.ElementsThrough(Order.class, "lines", "PartChanges.PART")),
      () -> OnTheRecord.EDITS.explain().toString()
    );
  }

  @Test
  @DisplayName("an edit mapper declared outside the owner's class and the pair's classes is not used without a via row")
  void ignoresAnElementMapperDeclaredElsewhere() {
    final var order = order();
    Unreached.EDITS.patchInto(order, new BoxOrder(null, List.of(new BoxChanges("abc"))));
    assertEquals(0, order.getLines().getFirst().getPrice(), "the line converts by name");
  }

  @Test
  @DisplayName("an element mapper assigned from another class's field is read from the field, once the class is built")
  void readsAnAliasedElementMapper() {
    final var order = order();
    Aliased.EDITS.patchInto(order, new OrderChanges(null, List.of(new LineChanges("abcd"))));
    assertEquals(40, order.getLines().getFirst().getPrice());
  }
}
