package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.forward;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapping.forward(src, tgt, fn)} — the forward-only sibling of {@code Mapping.to(src,
 * tgt, fwd, bwd)} for one-shot entity → DB schema mappings whose backward is never called. The user
 * supplies just the forward function; the row's backward, if invoked, throws {@link
 * UnsupportedOperationException} with a self-diagnosing message that names the row and the target
 * field.
 */
class MappingForwardTest {

  record Entity(Instant createdAt, String label) {}

  record Dto(String createdAtIso, String label) {}

  @Nested
  @DisplayName("forward direction — runs the user's transform")
  class ForwardRuns {

    @Test
    @DisplayName("forward transforms the source value into the target leaf")
    void forwardRuns() {
      final var mapper = Telescope.mapper(
        Entity.class,
        Dto.class,
        forward(Entity::createdAt, Dto::createdAtIso, Instant::toString)
      );

      final var entity = new Entity(Instant.parse("2026-01-01T00:00:00Z"), "x");
      assertEquals(new Dto("2026-01-01T00:00:00Z", "x"), mapper.forward(entity));
    }
  }

  @Nested
  @DisplayName("backward direction — unsupported")
  class Backward {

    @Test
    @DisplayName("backward throws UnsupportedOperationException with a self-diagnosing message")
    void backwardThrows() {
      final var mapper = Telescope.mapper(
        Entity.class,
        Dto.class,
        forward(Entity::createdAt, Dto::createdAtIso, Instant::toString)
      );

      final var dto = new Dto("2026-01-01T00:00:00Z", "x");
      final var ex = assertThrows(UnsupportedOperationException.class, () -> mapper.backward(dto));
      // Message should name the factory + the field so the failure is self-diagnosing.
      assertTrue(
        ex.getMessage().toLowerCase().contains("forward"),
        () -> "expected message to mention forward, was: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("createdAt"),
        () -> "expected message to mention the row's field, was: " + ex.getMessage()
      );
    }
  }
}
