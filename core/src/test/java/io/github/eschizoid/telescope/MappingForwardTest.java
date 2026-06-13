package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.forward;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapping.forward(src, tgt, fn)} — the forward-only row that produces a {@code
 * ForwardOnlyTransformTo} sealed permit. {@code Telescope.mapper(...)} REJECTS the row at the
 * factory boundary (compile-time-typed forward-only contract: the partial-Iso shape would silently
 * corrupt {@code Mapper.backward} / {@code Mapper.patch}). Callers must use {@code
 * Telescope.mapperForward(...)} to consume {@code forward(...)} rows.
 */
class MappingForwardTest {

  record Entity(Instant createdAt, String label) {}

  record Dto(String createdAtIso, String label) {}

  @Nested
  @DisplayName("Telescope.mapper(...) — REJECTS forward(...) rows at factory boundary")
  class Rejection {

    @Test
    @DisplayName("calling Telescope.mapper with a Mapping.forward row throws IAE naming the field + escape hatch")
    void mapperRejectsForward() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Entity.class, Dto.class, forward(Entity::createdAt, Dto::createdAtIso, Instant::toString))
      );
      assertTrue(ex.getMessage().contains("Telescope.mapper"));
      assertTrue(ex.getMessage().contains("Mapping.forward"));
      assertTrue(ex.getMessage().contains("createdAtIso"), () -> ex.getMessage());
      assertTrue(ex.getMessage().contains("mapperForward"));
    }
  }

  @Nested
  @DisplayName("Telescope.mapperForward(...) — accepts forward(...) rows")
  class ForwardOnly {

    @Test
    @DisplayName("forward direction runs the user's transform via mapperForward")
    void mapperForwardRuns() {
      final var projector = Telescope.mapperForward(
        Entity.class,
        Dto.class,
        forward(Entity::createdAt, Dto::createdAtIso, Instant::toString),
        to(Entity::label, Dto::label)
      );

      final var entity = new Entity(Instant.parse("2026-01-01T00:00:00Z"), "x");
      assertEquals(new Dto("2026-01-01T00:00:00Z", "x"), projector.forward(entity));
    }
  }
}
