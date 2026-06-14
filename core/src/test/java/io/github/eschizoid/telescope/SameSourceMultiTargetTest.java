package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins fan-out: one source field feeding multiple target components in a single {@link
 * Telescope#mapper(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)} call.
 *
 * <p>The real-world enterprise shape: a single audit field ({@code businessUnit}) that needs to
 * land on two correlated target columns ({@code cretnUserId} AND {@code lastUpdtdUserId}). Each row
 * in the call carries the same source accessor and a distinct target accessor.
 *
 * <p><b>Forward direction</b> broadcasts the source value to every target row correctly — the
 * load-bearing case for the enterprise pattern.
 *
 * <p><b>Backward direction</b> is non-bijective for the fan-out source field. The last registered
 * row wins the source-side rebuild slot, so the backward direction recovers the source from one
 * target's value. Round-trip equality holds when the user keeps the fan-out targets in sync
 * (typically same-typed copies of the same column); when they diverge, the test below pins the
 * last-row-wins behaviour so the asymmetry is explicit and ungated.
 */
class SameSourceMultiTargetTest {

  record SourceRow(String businessUnit, String payload) {}

  record TargetRow(String cretnUserId, String lastUpdtdUserId, String payload) {}

  @Nested
  @DisplayName("forward direction — fan-out broadcasts the source value to every target row")
  class ForwardFanout {

    @Test
    @DisplayName("two rows with the same source land on both target columns")
    void fanOutBroadcast() {
      final var src = new SourceRow("US-CENTRAL", "hello");

      // businessUnit fans out to BOTH cretnUserId AND lastUpdtdUserId.
      final var mapper = Telescope.mapper(
        SourceRow.class,
        TargetRow.class,
        to(SourceRow::businessUnit, TargetRow::cretnUserId),
        to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
      );

      final var out = mapper.forward(src);

      assertEquals("US-CENTRAL", out.cretnUserId(), "first row's target receives the source value");
      assertEquals("US-CENTRAL", out.lastUpdtdUserId(), "second row's target receives the same source value");
      assertEquals("hello", out.payload(), "unrelated same-named field still auto-binds");
    }
  }

  @Nested
  @DisplayName("backward direction — last-row-wins on the fan-out source field")
  class BackwardLastRowWins {

    @Test
    @DisplayName("backward recovers businessUnit from the LAST registered target row's value")
    void backwardPicksLast() {
      // Construct a divergent target — cretnUserId and lastUpdtdUserId disagree. The backward
      // direction must pick one to reconstruct businessUnit; by registration order, the last
      // row wins (lastUpdtdUserId).
      final var divergent = new TargetRow("first-target", "last-target", "hello");

      final var mapper = Telescope.mapper(
        SourceRow.class,
        TargetRow.class,
        to(SourceRow::businessUnit, TargetRow::cretnUserId),
        to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
      );

      final var back = mapper.backward(divergent);

      // The second row (lastUpdtdUserId) wins the source-side rebuild slot.
      assertEquals("last-target", back.businessUnit(), "last registered row wins the source rebuild");
      assertEquals("hello", back.payload());
    }

    @Test
    @DisplayName("round-trip is identity when fan-out targets stay in sync (the typical case)")
    void roundTripIdempotent() {
      final var mapper = Telescope.mapper(
        SourceRow.class,
        TargetRow.class,
        to(SourceRow::businessUnit, TargetRow::cretnUserId),
        to(SourceRow::businessUnit, TargetRow::lastUpdtdUserId)
      );

      final var src = new SourceRow("US-CENTRAL", "hello");
      assertEquals(src, mapper.backward(mapper.forward(src)));
    }
  }
}
