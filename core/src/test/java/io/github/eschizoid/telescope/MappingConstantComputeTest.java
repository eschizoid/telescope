package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the forward-only target-injection factories — {@code Mapping.constant} and
 * {@code Mapping.compute}. They mirror MapStruct's {@code @Mapping(constant)} /
 * {@code @Mapping(expression)} but stay in the same {@code Telescope.mapper(...)} call as the other
 * field rows.
 *
 * <p>The semantic these tests pin: forward stamps the value; backward silently drops it (the
 * rebuilt source carries the type default at the dual slot — same retraction shape as {@code
 * Mapping.drop} on the source side). Roundtripping {@code forward(backward(t))} does NOT preserve a
 * {@code compute} value by design.
 */
class MappingConstantComputeTest {

  record Src(String id, String name) {}

  record Tgt(
    String id,
    String name,
    String tenant,
    int apiVersion,
    Instant createdAt,
    UUID traceId,
    Map<String, String> metadata
  ) {}

  @Nested
  @DisplayName("Mapping.constant(Tgt::field, value) — eager literal")
  class ConstantRows {

    @Test
    @DisplayName("forward stamps the literal at the target slot")
    void forwardStampsLiteral() {
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::id, Tgt::id),
        to(Src::name, Tgt::name),
        constant(Tgt::tenant, "production"),
        constant(Tgt::apiVersion, 7),
        constant(Tgt::createdAt, Instant.EPOCH),
        constant(Tgt::traceId, new UUID(0, 0)),
        constant(Tgt::metadata, Map.of())
      );

      final var out = mapper.forward(new Src("o-1", "Alice"));
      assertEquals("o-1", out.id());
      assertEquals("Alice", out.name());
      assertEquals("production", out.tenant());
      assertEquals(7, out.apiVersion());
      assertEquals(Instant.EPOCH, out.createdAt());
      assertEquals(new UUID(0, 0), out.traceId());
      assertEquals(Map.of(), out.metadata());
    }

    @Test
    @DisplayName("backward silently drops the constant — src carries the type default at the dual slot")
    void backwardSilentlyDrops() {
      // Constant rows have no source slot. Backward direction reconstructs Src from Tgt; the
      // tenant/apiVersion/etc values are read by the engine but discarded.
      record Sbase(String id, String name, String junk) {}

      final var mapper = Telescope.mapper(
        Sbase.class,
        Tgt.class,
        to(Sbase::id, Tgt::id),
        to(Sbase::name, Tgt::name),
        constant(Tgt::tenant, "production"),
        constant(Tgt::apiVersion, 7),
        constant(Tgt::createdAt, Instant.EPOCH),
        constant(Tgt::traceId, new UUID(0, 0)),
        constant(Tgt::metadata, Map.of()),
        drop(Sbase::junk) // Src has a field with no tgt counterpart — drop it
      );

      final var back = mapper.backward(
        new Tgt("o-1", "Alice", "production", 7, Instant.EPOCH, new UUID(0, 0), Map.of())
      );
      assertEquals("o-1", back.id());
      assertEquals("Alice", back.name());
      assertNull(back.junk()); // dropped — Src field has type default (null for String)
    }
  }

  @Nested
  @DisplayName("Mapping.compute(Tgt::field, Supplier) — lazy, fresh per call")
  class ComputeRows {

    @Test
    @DisplayName("forward invokes the supplier on every call (fresh container identity)")
    void freshContainerPerCall() {
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::id, Tgt::id),
        to(Src::name, Tgt::name),
        constant(Tgt::tenant, "x"),
        constant(Tgt::apiVersion, 1),
        constant(Tgt::createdAt, Instant.EPOCH),
        constant(Tgt::traceId, new UUID(0, 0)),
        compute(Tgt::metadata, HashMap::new) // fresh allocation per call
      );

      final var t1 = mapper.forward(new Src("a", "A"));
      final var t2 = mapper.forward(new Src("b", "B"));
      assertNotSame(t1.metadata(), t2.metadata(), "compute should allocate a fresh map per forward call");
    }

    @Test
    @DisplayName("compute(Tgt::traceId, UUID::randomUUID) yields a different value on each forward")
    void freshUuidPerCall() {
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::id, Tgt::id),
        to(Src::name, Tgt::name),
        constant(Tgt::tenant, "x"),
        constant(Tgt::apiVersion, 1),
        constant(Tgt::createdAt, Instant.EPOCH),
        compute(Tgt::traceId, UUID::randomUUID),
        constant(Tgt::metadata, Map.of())
      );

      final var t1 = mapper.forward(new Src("a", "A"));
      final var t2 = mapper.forward(new Src("a", "A"));
      assertFalse(t1.traceId().equals(t2.traceId()), "UUID::randomUUID should produce distinct values");
    }

    @Test
    @DisplayName("compute rows are forward-only by design — forward(backward(t)) does NOT round-trip the value")
    void forwardBackwardForwardIsNotIdentity() {
      // The documented retraction: backward discards the slot; forward re-evaluates the supplier.
      // For a deterministic check, use a counter that increments each call.
      final var counter = new int[] { 0 };
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::id, Tgt::id),
        to(Src::name, Tgt::name),
        constant(Tgt::tenant, "x"),
        compute(Tgt::apiVersion, () -> ++counter[0]),
        constant(Tgt::createdAt, Instant.EPOCH),
        constant(Tgt::traceId, new UUID(0, 0)),
        constant(Tgt::metadata, Map.of())
      );

      final var original = new Tgt("o-1", "Alice", "x", 42, Instant.EPOCH, new UUID(0, 0), Map.of());
      // counter is 0; original.apiVersion is 42 (we constructed it directly, not via the mapper).
      final var src = mapper.backward(original); // backward discards apiVersion
      final var roundtripped = mapper.forward(src); // forward re-evaluates supplier → counter becomes 1

      assertEquals(42, original.apiVersion());
      assertEquals(1, roundtripped.apiVersion()); // NOT 42 — the slot is forward-only
      assertEquals("o-1", roundtripped.id());
      assertEquals("Alice", roundtripped.name());
    }
  }
}
