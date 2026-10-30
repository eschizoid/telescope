package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
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
        PairingMessages.fanOutBothWays("SourceRow", "TargetRow", "businessUnit", "cretnUserId", "lastUpdtdUserId"),
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
        PairingMessages.fanOutBothWays("SourceRow", "TargetRow", "businessUnit", "lastUpdtdUserId", "cretnUserId"),
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
        PairingMessages.fanOutBothWays("SourceRow", "TargetRow", "businessUnit", "cretnUserId", "lastUpdtdUserId"),
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
        PairingMessages.fanOutBothWays("Inner", "InnerDto", "businessUnit", "cretnUserId", "lastUpdtdUserId"),
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
}
