package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapper}'s four symmetric pre/post hooks ({@code beforeForward}, {@code afterForward},
 * {@code beforeBackward}, {@code afterBackward}) plus the source/target- aware {@link
 * java.util.function.BiFunction} overloads on the after-hooks. Each hook returns a fresh {@link
 * Mapper} with the hook folded into a per-direction {@link java.util.function.Function} / {@link
 * java.util.function.BiFunction} field — no megamorphic {@link
 * io.github.eschizoid.telescope.internal.optics.Iso} chain regardless of chain depth.
 *
 * <p>Hooks use {@code Function<X, X>} (or {@code BiFunction}) — not {@code Consumer<X>} — so they
 * work for both immutable records and mutable beans.
 */
class MapperPostHooksTest {

  record Entity(String id, String firstName, String lastName, Long updatedAtMillis) {}

  record Dto(String id, String fullName, Long updatedAtMillis) {}

  // DeepMap is strict on missing rows; drop firstName/lastName so the structural step doesn't
  // reject the mismatch — the hooks supply fullName cross-cuttingly in these tests.
  private static Mapper<Entity, Dto> baseMapper() {
    return Telescope.mapper(
      Entity.class,
      Dto.class,
      to(Entity::id, Dto::id),
      drop(Entity::firstName),
      drop(Entity::lastName),
      to(Entity::updatedAtMillis, Dto::updatedAtMillis),
      to(Entity::firstName, Dto::fullName)
    );
  }

  @Nested
  @DisplayName("beforeForward — runs before the structural forward direction reads the source")
  class BeforeForward {

    @Test
    @DisplayName("hook rewrites the source seen by the forward conversion")
    void hookRewritesSource() {
      final Mapper<Entity, Dto> mapper = baseMapper().beforeForward(e ->
        new Entity(e.id().trim(), e.firstName(), e.lastName(), e.updatedAtMillis())
      );

      final var out = mapper.forward(new Entity("  e-1  ", "Alice", null, null));
      assertEquals("e-1", out.id(), "trimmed id from beforeForward hook lands on the dto");
    }
  }

  @Nested
  @DisplayName("afterForward — runs after the structural forward direction produces the target")
  class AfterForward {

    @Test
    @DisplayName("Function<B, B> hook rewrites the forward output")
    void hookRewritesForwardOutput() {
      final Mapper<Entity, Dto> mapper = baseMapper().afterForward(d -> new Dto(d.id(), d.fullName(), 12345L));

      assertEquals(12345L, mapper.forward(new Entity("e-1", "Alice", "X", null)).updatedAtMillis());
    }

    @Test
    @DisplayName("BiFunction<A, B, B> hook sees both source and target — typed denormalization")
    void biFunctionSeesSource() {
      final Mapper<Entity, Dto> mapper = baseMapper().afterForward((src, dto) ->
        new Dto(dto.id(), src.firstName() + " " + src.lastName(), dto.updatedAtMillis())
      );

      final var out = mapper.forward(new Entity("e-1", "Alice", "Wonderland", null));
      assertEquals("Alice Wonderland", out.fullName());
    }

    @Test
    @DisplayName("returns a new Mapper — the original is unchanged")
    void returnsNewMapperInstance() {
      final Mapper<Entity, Dto> base = baseMapper();
      final Mapper<Entity, Dto> withHook = base.afterForward(d -> new Dto(d.id(), d.fullName(), 99L));

      assertNotSame(base, withHook);
      assertEquals(null, base.forward(new Entity("e-1", "A", null, null)).updatedAtMillis());
      assertEquals(99L, withHook.forward(new Entity("e-1", "A", null, null)).updatedAtMillis());
    }

    @Test
    @DisplayName("chained Function hooks compose left-to-right (first call runs first)")
    void hooksComposeInOrder() {
      final Mapper<Entity, Dto> mapper = baseMapper()
        .afterForward(d -> new Dto(d.id(), d.fullName() + "-1", d.updatedAtMillis()))
        .afterForward(d -> new Dto(d.id(), d.fullName() + "-2", d.updatedAtMillis()));

      // fullName is null initially (no row maps it); 1st hook turns "null-1", 2nd → "null-1-2"
      assertEquals("null-1-2", mapper.forward(new Entity("e-1", null, null, null)).fullName());
    }

    @Test
    @DisplayName("chained BiFunction hooks also compose left-to-right with source access throughout")
    void biFunctionHooksCompose() {
      final Mapper<Entity, Dto> mapper = baseMapper()
        .afterForward((src, dto) -> new Dto(dto.id(), src.firstName().toUpperCase(), dto.updatedAtMillis()))
        .afterForward((src, dto) ->
          new Dto(dto.id(), dto.fullName() + " (" + src.lastName() + ")", dto.updatedAtMillis())
        );

      assertEquals("ALICE (Wonderland)", mapper.forward(new Entity("e-1", "Alice", "Wonderland", null)).fullName());
    }
  }

  @Nested
  @DisplayName("beforeBackward — runs before the structural backward direction consumes the target")
  class BeforeBackward {

    @Test
    @DisplayName("hook rewrites the input target value seen by backward")
    void hookRewritesBackwardInput() {
      final Mapper<Entity, Dto> mapper = baseMapper().beforeBackward(d ->
        new Dto(d.id().trim(), d.fullName(), d.updatedAtMillis())
      );

      final var entity = mapper.backward(new Dto("  e-1  ", "Alice", 42L));
      assertEquals("e-1", entity.id());
    }
  }

  @Nested
  @DisplayName("afterBackward — runs after the structural backward direction produces the source")
  class AfterBackward {

    @Test
    @DisplayName("Function<A, A> hook rewrites the rebuilt source")
    void hookRewritesBackwardOutput() {
      final Mapper<Entity, Dto> mapper = baseMapper().afterBackward(e ->
        new Entity(e.id(), e.firstName(), e.lastName(), 999L)
      );

      assertEquals(999L, mapper.backward(new Dto("e-1", "x", null)).updatedAtMillis());
    }

    @Test
    @DisplayName("BiFunction<B, A, A> hook sees both target and source — audit-stamping pattern")
    void biFunctionSeesTarget() {
      final Mapper<Entity, Dto> mapper = baseMapper().afterBackward((dto, entity) ->
        new Entity(entity.id(), entity.firstName(), entity.lastName(), dto.updatedAtMillis())
      );

      final var entity = mapper.backward(new Dto("e-1", "x", 555L));
      assertEquals(
        555L,
        entity.updatedAtMillis(),
        "BiFunction read the dto's audit field and stamped it on the entity"
      );
    }
  }

  @Nested
  @DisplayName("hooks fire only on their direction — laziness contract")
  class HookLaziness {

    @Test
    @DisplayName("each of the four hooks fires on exactly its direction; not the other")
    void hooksFireOnlyOnTheirDirection() {
      final var beforeFwd = new AtomicInteger();
      final var afterFwd = new AtomicInteger();
      final var beforeBwd = new AtomicInteger();
      final var afterBwd = new AtomicInteger();

      final Mapper<Entity, Dto> mapper = baseMapper()
        .beforeForward(e -> {
          beforeFwd.incrementAndGet();
          return e;
        })
        .afterForward(d -> {
          afterFwd.incrementAndGet();
          return d;
        })
        .beforeBackward(d -> {
          beforeBwd.incrementAndGet();
          return d;
        })
        .afterBackward(e -> {
          afterBwd.incrementAndGet();
          return e;
        });

      mapper.forward(new Entity("e-1", "A", null, null));
      assertEquals(1, beforeFwd.get(), "beforeForward fired on forward()");
      assertEquals(1, afterFwd.get(), "afterForward fired on forward()");
      assertEquals(0, beforeBwd.get(), "beforeBackward did NOT fire on forward()");
      assertEquals(0, afterBwd.get(), "afterBackward did NOT fire on forward()");

      mapper.backward(new Dto("e-1", "A", null));
      assertEquals(1, beforeFwd.get(), "beforeForward did NOT fire on backward()");
      assertEquals(1, afterFwd.get(), "afterForward did NOT fire on backward()");
      assertEquals(1, beforeBwd.get(), "beforeBackward fired on backward()");
      assertEquals(1, afterBwd.get(), "afterBackward fired on backward()");
    }
  }
}
