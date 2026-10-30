package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins fan-out: one source field feeding several target components, one row per target.
 *
 * <p>A single audit field ({@code businessUnit}) lands on two correlated target columns ({@code
 * cretnUserId} and {@code lastUpdtdUserId}). A forward-only mapper broadcasts the source value to
 * every target its rows name. A mapper that converts both ways rebuilds each source field from one
 * target, so it refuses a second target by name whichever order the rows come in.
 */
class SameSourceMultiTargetTest {

  record SourceRow(String businessUnit, String payload) {}

  record TargetRow(String cretnUserId, String lastUpdtdUserId, String payload) {}

  @Nested
  @DisplayName("a forward-only mapper broadcasts the source value to every target row")
  class ForwardFanout {

    @Test
    @DisplayName("two rows with the same source land on both target columns")
    void fanOutBroadcast() {
      final var mapper = Telescope.mapperForward(
        SourceRow.class,
        TargetRow.class,
        to(SourceRow::businessUnit, TargetRow::cretnUserId),
        to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
      );

      final var out = mapper.forward(new SourceRow("US-CENTRAL", "hello"));

      assertEquals("US-CENTRAL", out.cretnUserId(), "first row's target receives the source value");
      assertEquals("US-CENTRAL", out.lastUpdtdUserId(), "second row's target receives the same source value");
      assertEquals("hello", out.payload(), "unrelated same-named field still auto-binds");
    }
  }

  @Nested
  @DisplayName("a mapper that converts both ways refuses a fan-out by name")
  class TwoWayFanout {

    @Test
    @DisplayName("the second target refused is the one the later row names")
    void fanOutRefused() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          SourceRow.class,
          TargetRow.class,
          to(SourceRow::businessUnit, TargetRow::cretnUserId),
          to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
        )
      );
      assertEquals(
        PairingMessages.fanOutBothWays(
          "SourceRow",
          "TargetRow",
          "businessUnit",
          List.of("cretnUserId", "lastUpdtdUserId")
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("the rows given the other way round are refused naming the targets in that order")
    void fanOutRefusedInEitherOrder() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          SourceRow.class,
          TargetRow.class,
          to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId),
          to(SourceRow::businessUnit, TargetRow::cretnUserId)
        )
      );
      assertEquals(
        PairingMessages.fanOutBothWays(
          "SourceRow",
          "TargetRow",
          "businessUnit",
          List.of("lastUpdtdUserId", "cretnUserId")
        ),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("Telescope.map, which converts both ways too, refuses the same fan-out")
    void fanOutRefusedByMap() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(
          SourceRow.class,
          TargetRow.class,
          to(SourceRow::businessUnit, TargetRow::cretnUserId),
          to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
        )
      );
      assertEquals(
        PairingMessages.fanOutBothWays(
          "SourceRow",
          "TargetRow",
          "businessUnit",
          List.of("cretnUserId", "lastUpdtdUserId")
        ),
        refused.getMessage()
      );
    }
  }

  record Inner(String businessUnit) {}

  record InnerDto(String cretnUserId, String lastUpdtdUserId) {}

  record Outer(Inner inner) {}

  record OuterDto(InnerDto inner) {}

  @Nested
  @DisplayName("rows keyed to a nested pair are held to the same rule there")
  class NestedFanout {

    @Test
    @DisplayName("a two-way mapper refuses a fan-out keyed to the pair it recurses into")
    void nestedFanOutRefused() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          Outer.class,
          OuterDto.class,
          to(Inner::businessUnit, InnerDto::cretnUserId),
          to(Inner::businessUnit, InnerDto::lastUpdtdUserId)
        )
      );
      assertEquals(
        PairingMessages.fanOutBothWays("Inner", "InnerDto", "businessUnit", List.of("cretnUserId", "lastUpdtdUserId")),
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("a forward-only mapper broadcasts a fan-out keyed to the pair it recurses into")
    void nestedFanOutBroadcast() {
      final var mapper = Telescope.mapperForward(
        Outer.class,
        OuterDto.class,
        to(Inner::businessUnit, InnerDto::cretnUserId),
        to(Inner::businessUnit, InnerDto::lastUpdtdUserId)
      );
      assertEquals(
        new OuterDto(new InnerDto("US-CENTRAL", "US-CENTRAL")),
        mapper.forward(new Outer(new Inner("US-CENTRAL")))
      );
    }
  }

  @Nested
  @DisplayName("a field both dropped and written is refused by name on every factory")
  class DroppedAndWritten {

    @Test
    @DisplayName("a drop before a write is refused by a two-way mapper")
    void dropThenWrite() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          SourceRow.class,
          TargetRow.class,
          drop(SourceRow::businessUnit),
          to(SourceRow::businessUnit, TargetRow::cretnUserId)
        )
      );
      assertEquals(PairingMessages.droppedAndWritten("SourceRow", "TargetRow", "businessUnit"), refused.getMessage());
    }

    @Test
    @DisplayName("a write before a drop is refused by a forward-only mapper")
    void writeThenDrop() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapperForward(
          SourceRow.class,
          TargetRow.class,
          to(SourceRow::businessUnit, TargetRow::cretnUserId),
          drop(SourceRow::businessUnit)
        )
      );
      assertEquals(PairingMessages.droppedAndWritten("SourceRow", "TargetRow", "businessUnit"), refused.getMessage());
    }
  }

  record Src(String n) {}

  record Tgt(String a, String b) {}

  record Three(String a, String b, String c) {}

  record Ad(String a, String b) {}

  record Deep(Ad addr) {}

  record Single(String a) {}

  private static final Telescope<Src, String> SRC_N = Telescope.of(Src.class).field(Src::n);

  private static Telescope<Tgt, String> tgt(final Telescope.Accessor<Tgt, String> property) {
    return Telescope.of(Tgt.class).field(property);
  }

  /** The message of the refusal building {@code mapper} throws. */
  private static String refused(final Runnable mapper) {
    return assertThrows(IllegalArgumentException.class, mapper::run).getMessage();
  }

  private static String fanOut(final String target, final String... targets) {
    return PairingMessages.fanOutBothWays("Src", target, "n", List.of(targets));
  }

  @Nested
  @DisplayName("a row with a path on one side writes the property as an accessor row does")
  class PathRows {

    @Test
    @DisplayName("two source paths one field hop long are refused in the order given")
    void twoSourcePaths() {
      assertEquals(
        fanOut("Tgt", "a", "b"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(SRC_N, Tgt::a), to(SRC_N, Tgt::b)))
      );
      assertEquals(
        fanOut("Tgt", "b", "a"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(SRC_N, Tgt::b), to(SRC_N, Tgt::a)))
      );
    }

    @Test
    @DisplayName("two target paths fed by one accessor are refused in the order given")
    void twoTargetPaths() {
      assertEquals(
        fanOut("Tgt", "a", "b"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(Src::n, tgt(Tgt::a)), to(Src::n, tgt(Tgt::b))))
      );
      assertEquals(
        fanOut("Tgt", "b", "a"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(Src::n, tgt(Tgt::b)), to(Src::n, tgt(Tgt::a))))
      );
    }

    @Test
    @DisplayName("a path row beside an accessor row is refused, either side a path, either order")
    void pathBesideAccessor() {
      assertEquals(
        fanOut("Tgt", "a", "b"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(Src::n, tgt(Tgt::a)), to(Src::n, Tgt::b)))
      );
      assertEquals(
        fanOut("Tgt", "b", "a"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::b), to(Src::n, tgt(Tgt::a))))
      );
      assertEquals(
        fanOut("Tgt", "a", "b"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(SRC_N, Tgt::a), to(Src::n, Tgt::b)))
      );
      assertEquals(
        fanOut("Tgt", "b", "a"),
        refused(() -> Telescope.mapper(Src.class, Tgt.class, to(Src::n, Tgt::b), to(SRC_N, Tgt::a)))
      );
    }

    @Test
    @DisplayName("target paths below a property are read back too, and named by the properties they step through")
    void deepTargetPaths() {
      final var a = Telescope.of(Deep.class).field(Deep::addr).field(Ad::a);
      final var b = Telescope.of(Deep.class).field(Deep::addr).field(Ad::b);
      assertEquals(
        fanOut("Deep", "addr.a", "addr.b"),
        refused(() -> Telescope.mapper(Src.class, Deep.class, to(Src::n, a), to(Src::n, b)))
      );
      assertEquals(
        fanOut("Deep", "addr.b", "addr.a"),
        refused(() -> Telescope.mapper(Src.class, Deep.class, to(Src::n, b), to(Src::n, a)))
      );
    }

    @Test
    @DisplayName("three targets are named once each, whichever kind of row writes them")
    void threeTargets() {
      assertEquals(
        fanOut("Three", "a", "b", "c"),
        refused(() ->
          Telescope.mapper(
            Src.class,
            Three.class,
            to(Src::n, Three::a),
            to(Src::n, Telescope.of(Three.class).field(Three::b)),
            to(Src::n, Three::c)
          )
        )
      );
    }

    @Test
    @DisplayName("a fan-out is refused before a target property the rows leave unwritten")
    void fanOutBeforeAnUnwrittenTarget() {
      assertEquals(
        fanOut("Three", "a", "b"),
        refused(() -> Telescope.mapper(Src.class, Three.class, to(Src::n, Three::a), to(Src::n, Three::b)))
      );
    }

    @Test
    @DisplayName("a forward-only mapper broadcasts through path rows")
    void forwardOnlyBroadcasts() {
      final var mapper = Telescope.mapperForward(Src.class, Tgt.class, to(SRC_N, Tgt::a), to(Src::n, tgt(Tgt::b)));
      assertEquals(new Tgt("v", "v"), mapper.forward(new Src("v")));
    }

    @Test
    @DisplayName("a row a when(...) gates is not read back, so it writes no second target")
    void gatedPathIsNoSecondTarget() {
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::n, Tgt::a),
        Mapping.<Src, Tgt>when(s -> true, to(Src::n, tgt(Tgt::b)))
      );
      assertEquals(new Src("A"), mapper.backward(new Tgt("A", "B")));
    }

    @Test
    @DisplayName("a drop beside a path row writing the property is refused, either side a path, either order")
    void dropBesidePath() {
      final var dropped = PairingMessages.droppedAndWritten("Src", "Single", "n");
      final var single = Telescope.of(Single.class).field(Single::a);
      assertEquals(dropped, refused(() -> Telescope.mapper(Src.class, Single.class, drop(Src::n), to(Src::n, single))));
      assertEquals(dropped, refused(() -> Telescope.mapper(Src.class, Single.class, to(Src::n, single), drop(Src::n))));
      assertEquals(
        dropped,
        refused(() -> Telescope.mapperForward(Src.class, Single.class, drop(Src::n), to(SRC_N, Single::a)))
      );
      assertEquals(
        dropped,
        refused(() -> Telescope.mapperForward(Src.class, Single.class, to(SRC_N, Single::a), drop(Src::n)))
      );
    }
  }
}
