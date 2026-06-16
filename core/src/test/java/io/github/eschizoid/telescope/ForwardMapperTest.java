package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link Telescope#mapperForward(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} — the forward-only factory that returns a
 * {@link ForwardMapper} whose backward direction is not present at the type level. MapStruct cannot
 * express "this mapper is one-way" in its type system at all; this is the differentiator the type
 * system buys.
 *
 * <p>The compile-time enforcement is invisible in JUnit (you can't write a test that asserts a
 * method does NOT exist), so the negative case is implicit in the rest of the call site: there is
 * no {@code mapper.backward(...)} to call anywhere in this file. The positive surface (forward,
 * read alias, sourceClass / targetClass) is pinned below.
 */
class ForwardMapperTest {

  record Entity(String id, Instant createdAt, String firstName, String lastName) {}

  record Dto(String id, String createdAtIso, String fullName, String tenant) {}

  @Test
  @DisplayName("ForwardMapper assembles a typed forward-only result with toOneWay / constant rows")
  void forwardOnlyProjector() {
    final ForwardMapper<Entity, Dto> projector = Telescope.mapperForward(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      toOneWay(Entity::createdAt, Dto::createdAtIso, Instant::toString),
      to(Entity::firstName, Dto::fullName),
      constant(Dto::tenant, "production")
    );

    final var dto = projector.forward(new Entity("e-1", Instant.parse("2026-01-01T00:00:00Z"), "Alice", "Wonderland"));

    assertEquals("e-1", dto.id());
    assertEquals("2026-01-01T00:00:00Z", dto.createdAtIso());
    assertEquals("Alice", dto.fullName());
    assertEquals("production", dto.tenant());
  }

  @Test
  @DisplayName("read(...) is an alias of forward(...)")
  void readIsAlias() {
    final ForwardMapper<Entity, Dto> projector = Telescope.mapperForward(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      toOneWay(Entity::createdAt, Dto::createdAtIso, Instant::toString),
      to(Entity::firstName, Dto::fullName),
      constant(Dto::tenant, "x")
    );

    final var input = new Entity("e-2", Instant.parse("2026-02-02T00:00:00Z"), "B", "C");
    assertEquals(projector.forward(input), projector.read(input));
  }

  @Test
  @DisplayName(".then composes two forward-only projections via the Getter lattice substrate")
  void thenComposes() {
    record Audit(String summary) {}

    final ForwardMapper<Entity, Dto> entityToDto = Telescope.mapperForward(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      toOneWay(Entity::createdAt, Dto::createdAtIso, Instant::toString),
      to(Entity::firstName, Dto::fullName),
      constant(Dto::tenant, "p")
    );
    final ForwardMapper<Dto, Audit> dtoToAudit = ForwardMapper.create(
      d -> new Audit(d.id() + ":" + d.fullName() + "@" + d.tenant()),
      Dto.class,
      Audit.class
    );

    final ForwardMapper<Entity, Audit> pipeline = entityToDto.then(dtoToAudit);
    final var out = pipeline.forward(new Entity("e-1", Instant.parse("2026-01-01T00:00:00Z"), "Alice", "X"));
    assertEquals(new Audit("e-1:Alice@p"), out);
    assertEquals(Entity.class, pipeline.sourceClass());
    assertEquals(Audit.class, pipeline.targetClass());
  }

  @Test
  @DisplayName("sourceClass / targetClass expose the typed pair")
  void exposesClasses() {
    final ForwardMapper<Entity, Dto> projector = Telescope.mapperForward(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      toOneWay(Entity::createdAt, Dto::createdAtIso, Instant::toString),
      to(Entity::firstName, Dto::fullName),
      constant(Dto::tenant, "x")
    );

    assertEquals(Entity.class, projector.sourceClass());
    assertEquals(Dto.class, projector.targetClass());
  }
}
