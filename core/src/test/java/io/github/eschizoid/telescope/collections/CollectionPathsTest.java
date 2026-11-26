package io.github.eschizoid.telescope.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a collection is written, held to one result on the generated and the runtime path where both
 * build the pair: what is never inferred, what is never re-pointed, which method is the adder,
 * which values go through it unconverted, which bean the adder alone makes buildable, and what each
 * path's {@code explain()} reports.
 */
class CollectionPathsTest {

  /** Each path's mapper of one pair, by the path's name. */
  private static <A, B> Map<String, Mapper<A, B>> paths(final Mapper<A, B> generated, final Mapper<A, B> runtime) {
    return Map.of("generated", generated, "runtime", runtime);
  }

  @Test
  @DisplayName(
    "a property of the owner's type that nothing declares a back-reference is left alone by the runtime mapper," +
      " which the generated bridge refuses to leave unpaired"
  )
  void anOwnerTypedPropertyIsNoBackReference() {
    final var mapper = Telescope.mapper(ROrderDto.class, ROrder.class);
    final var built = mapper.forward(new ROrderDto("o", List.of(new RLineDto("a"))));
    assertNull(built.getLines().getFirst().getReplacedFrom());
    final var held = new ROrder();
    final var earlier = new ROrder();
    final var kept = new RLine();
    kept.setReplacedFrom(earlier);
    held.getLines().add(kept);
    mapper.into(held, new ROrderDto("held", List.of(new RLineDto("a"))));
    assertNull(held.getLines().getFirst().getReplacedFrom());
    assertSame(earlier, kept.getReplacedFrom());
  }

  @Test
  @DisplayName("a method taking an interface the element implements, named for another list, is no adder")
  void anUnrelatedWiderMethodIsNoAdder() {
    for (final var path : paths(CollectionMappers.Z, Telescope.mapper(ZOrderDto.class, ZOrder.class)).entrySet()) {
      final var built = path.getValue().forward(new ZOrderDto("z", List.of(new ZLineDto("a"))));
      assertEquals("[a]", built.getLines().toString(), path.getKey());
      assertEquals(List.of(), built.attachments, path.getKey());
      assertEquals(List.of("ZOrder.lines: refilled in place"), collections(path.getValue()), path.getKey());
    }
  }

  @Test
  @DisplayName("two adders no name decides between are left unused, and the lines go through the setter")
  void undecidedAddersAreLeftUnused() {
    final var mapper = Telescope.mapper(KOrderDto.class, KOrder.class);
    final var built = mapper.forward(new KOrderDto(List.of(new KLineDto("a"))));
    assertEquals("[a]", built.getLines().toString());
    assertEquals(List.of(), built.other);
    final var held = new KOrder();
    mapper.into(held, new KOrderDto(List.of(new KLineDto("b"))));
    assertEquals("[b]", held.getLines().toString());
    assertEquals(List.of(), held.other);
    assertEquals(
      List.of(
        "KOrder.lines: refilled in place; addBackup(KLine) and addPrimary(KLine) left unused: no name says which" +
          " belongs to 'lines'"
      ),
      collections(mapper)
    );
  }

  @Test
  @DisplayName("the runtime mapper warns once, naming the adders it leaves unused, in the shared words")
  void theRuntimeWarnsOfUnusedAdders() {
    final var jul = Logger.getLogger(Telescope.class.getName());
    final var records = new ArrayList<String>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        if (record.getLevel() == Level.WARNING) records.add(record.getMessage());
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    jul.addHandler(handler);
    try {
      Telescope.mapper(KOrderDto.class, KOrder.class);
    } finally {
      jul.removeHandler(handler);
    }
    assertEquals(
      List.of(
        PairingMessages.collectionMemberUnused(
          "KOrder",
          "lines",
          "KLine",
          "adder",
          List.of("addBackup(KLine)", "addPrimary(KLine)"),
          "addLine"
        )
      ),
      records
    );
  }

  @Test
  @DisplayName("two adders no name decides between, on a list with no setter, refuse the mapper with both named")
  void undecidedAddersWithNoOtherWayInAreRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(KOrderDto.class, KFixedOrder.class)
    );
    assertEquals(
      PairingMessages.collectionMemberUndecided(
        "KFixedOrder",
        "lines",
        "KLine",
        "adder",
        List.of("addBackup(KLine)", "addPrimary(KLine)"),
        "addLine"
      ),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("an adder a generic superclass declares over its type variable fills the subclass's items")
  void anInheritedGenericAdderFills() {
    for (final var path : paths(CollectionMappers.G, Telescope.mapper(GLineBoxDto.class, GLineBox.class)).entrySet()) {
      final var built = path.getValue().forward(new GLineBoxDto(List.of(new GLineDto("a"))));
      assertEquals("[a]", built.getItems().toString(), path.getKey());
      assertEquals(List.of("addItem"), built.calls, path.getKey());
      assertEquals(
        List.of("GLineBox.items: through GLineBox.addItem(GLine)"),
        collections(path.getValue()),
        path.getKey()
      );
    }
  }

  @Test
  @DisplayName("strings handed across go through the adder, which keeps each tag once, on forward, into and patch")
  void valuesHandedAcrossGoThroughTheAdder() {
    final var sent = new ATaggedDto("t", List.of("x", "x", "y"));
    for (final var path : paths(CollectionMappers.A, Telescope.mapper(ATaggedDto.class, ATagged.class)).entrySet()) {
      assertEquals(List.of("x", "y"), path.getValue().forward(sent).getTags(), path.getKey());
      final var held = new ATagged();
      final var list = held.getTags();
      path.getValue().into(held, sent);
      assertEquals(List.of("x", "y"), held.getTags(), path.getKey());
      assertSame(list, held.getTags(), path.getKey());
      assertEquals(
        List.of("ATagged.tags: through ATagged.addTag(String)"),
        collections(path.getValue()),
        path.getKey()
      );
    }
    for (final var path : paths(
      CollectionMappers.A_BACK,
      Telescope.mapper(ATagged.class, ATaggedDto.class)
    ).entrySet()) {
      assertEquals(List.of("x", "y"), path.getValue().patch(new ATagged(), sent).getTags(), path.getKey());
    }
  }

  private static MOrder heldOrder(final String name, final String... skus) {
    final var order = new MOrder();
    order.setName(name);
    for (final var sku : skus) {
      final var line = new MLine();
      line.setSku(sku);
      line.setOrder(order);
      order.getLines().add(line);
    }
    return order;
  }

  @Test
  @DisplayName("a copy of the same class hands the source's lines across, through neither the adder nor the remover")
  void aCopyHandsTheSourcesLinesAcross() {
    for (final var path : paths(CollectionMappers.M_COPY, Telescope.mapper(MOrder.class, MOrder.class)).entrySet()) {
      final var source = heldOrder("source", "a");
      final var copy = path.getValue().forward(source);
      assertSame(source.getLines().getFirst(), copy.getLines().getFirst(), path.getKey());
      assertSame(source, source.getLines().getFirst().getOrder(), path.getKey());
      assertEquals(List.of(), copy.calls, path.getKey());
      final var held = heldOrder("held", "old");
      final var old = held.getLines().getFirst();
      path.getValue().into(held, source);
      assertEquals(List.of(), held.calls, path.getKey());
      assertSame(source, source.getLines().getFirst().getOrder(), path.getKey());
      assertSame(held, old.getOrder(), path.getKey());
    }
  }

  @Test
  @DisplayName("converted lines go through the adder and the dropped one through the remover, on both paths")
  void convertedLinesGoThroughTheAdderAndTheRemover() {
    for (final var path : paths(CollectionMappers.M, Telescope.mapper(MOrderDto.class, MOrder.class)).entrySet()) {
      final var held = heldOrder("held", "keep", "drop");
      final var drop = held.getLines().get(1);
      path.getValue().into(held, new MOrderDto("held", List.of(new MLineDto("keep"), new MLineDto("new"))));
      assertEquals("[keep->held, new->held]", held.getLines().toString(), path.getKey());
      assertEquals(List.of("add keep", "add new", "remove drop"), held.calls, path.getKey());
      assertNull(drop.getOrder(), path.getKey());
    }
  }

  @Test
  @DisplayName("an adder named for the collection whose parameter is a superclass of the element still fills it")
  void aWiderAdderStillFills() {
    for (final var path : paths(CollectionMappers.W, Telescope.mapper(WOrderDto.class, WOrder.class)).entrySet()) {
      final var built = path.getValue().forward(new WOrderDto("o", List.of(new WLineDto("a"))));
      assertEquals("[a!]", built.getLines().toString(), path.getKey());
      assertEquals(List.of("WOrder.lines: through WOrder.addLine(WBase)"), collections(path.getValue()), path.getKey());
    }
  }

  @Test
  @DisplayName("a bean with an adder and no setter at all is built through the adder, with no row, on both paths")
  void aBeanWithOnlyAnAdderIsBuilt() {
    for (final var path : paths(CollectionMappers.H, Telescope.mapper(HOrderDto.class, HOrder.class)).entrySet()) {
      final var built = path.getValue().forward(new HOrderDto(List.of(new HLineDto("a"))));
      assertEquals("[a]", built.getLines().toString(), path.getKey());
    }
  }

  private static List<String> collections(final Mapper<?, ?> mapper) {
    return mapper.explain().collections().stream().map(OpticNode.CollectionWrite::toString).sorted().toList();
  }

  @Test
  @DisplayName("explain reports the same collection lines on the generated and the runtime mapper")
  void bothPathsExplainAlike() {
    assertEquals(
      List.of("MOrder.lines: through MOrder.addLine(MLine), removed through MOrder.removeLine(MLine)"),
      collections(CollectionMappers.M)
    );
    assertEquals(collections(Telescope.mapper(MOrderDto.class, MOrder.class)), collections(CollectionMappers.M));
  }

  @Test
  @DisplayName("a pair of one class both ways reports each collection once")
  void aPairOfOneClassReportsEachCollectionOnce() {
    assertEquals(List.of("MOrder.lines: refilled in place"), collections(Telescope.mapper(MOrder.class, MOrder.class)));
    assertEquals(List.of("MOrder.lines: refilled in place"), collections(CollectionMappers.M_COPY));
  }
}
