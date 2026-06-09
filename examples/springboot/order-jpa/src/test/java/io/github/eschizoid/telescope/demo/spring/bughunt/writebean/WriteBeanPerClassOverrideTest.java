package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code writeBean(Class, STRATEGY)} precedence over {@code writeBeans(STRATEGY)}.
 *
 * <ul>
 *   <li>{@link BoxedOrderEntity} is constructed via {@code SETTERS} (it carries a no-arg ctor and
 *       setters but no all-args ctor / builder, so SETTERS is the only sensible choice).
 *   <li>{@link ShippingNote} <em>must</em> be constructed via {@code CONSTRUCTOR}: there are no
 *       setters, no builder, no no-arg ctor. The per-class hint is the only thing keeping the deep
 *       recursion alive.
 * </ul>
 *
 * <p>If the engine ignores the per-class override and tries to apply {@code SETTERS} to {@code
 * ShippingNote}, mapper construction would fail eagerly at {@code Telescope.mapper(...)} time when
 * the SETTERS writer probe ran. If the override is wired but consulted in the wrong order
 * (default-first instead of class-first), the same failure surfaces.
 */
class WriteBeanPerClassOverrideTest {

  @Test
  void perClassOverrideWinsOverWriteBeansDefault() {
    final var mapper = BoxedOrderMappers.boxedOrderMapper();
    final var source = new BoxedOrder(
      7L,
      "BOX-1",
      List.of(new NoteIn("FRAGILE", "handle with care"), new NoteIn("HEAVY", "use two hands"))
    );

    final var entity = mapper.forward(source);

    assertThat(entity).isNotNull();
    assertThat(entity.getId()).isEqualTo(7L);
    assertThat(entity.getLabel()).isEqualTo("BOX-1");
    assertThat(entity.getNotes()).hasSize(2);
    // The fact that getNotes() returned populated ShippingNote instances at all proves CONSTRUCTOR
    // fired — SETTERS would have failed eagerly (no no-arg ctor on ShippingNote), and the unused
    // hint validator would have rejected a no-op override.
    assertThat(entity.getNotes().get(0).getCode()).isEqualTo("FRAGILE");
    assertThat(entity.getNotes().get(0).getText()).isEqualTo("handle with care");
    assertThat(entity.getNotes().get(1).getCode()).isEqualTo("HEAVY");
    assertThat(entity.getNotes().get(1).getText()).isEqualTo("use two hands");

    final var roundTrip = mapper.backward(entity);
    assertThat(roundTrip).isEqualTo(source);
  }
}
