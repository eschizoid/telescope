package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapping.toOneWay(...)} — a name-only alias of {@code Mapping.forward(...)} added in
 * Enh 8. Adopters can statically import {@code toOneWay} alongside {@code to} where the bare name
 * {@code forward} would collide with common local variable names. Behaviour is identical.
 */
class MappingToOneWayAliasTest {

  record Entity(String id, Instant createdAt) {}

  record Dto(String id, String createdAtIso) {}

  @Test
  @DisplayName("Mapping.toOneWay produces a row that behaves identically to Mapping.forward")
  void toOneWayBehavesLikeForward() {
    final var mapper = Telescope.mapperForward(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      toOneWay(Entity::createdAt, Dto::createdAtIso, Instant::toString)
    );
    final var entity = new Entity("o1", Instant.parse("2026-01-01T00:00:00Z"));
    final var dto = mapper.forward(entity);
    assertEquals("o1", dto.id());
    assertEquals("2026-01-01T00:00:00Z", dto.createdAtIso());
  }
}
