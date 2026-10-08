package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.BuilderUserTelescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.DataUserTelescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lombok navigators record the hop keys a hand-written bean path records, so their edits fuse with
 * hand-written ones. Lives in the core package to read the fusion record and {@code Fusion.fuse}.
 */
class LombokNavigatorFusionTest {

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <S> boolean fuses(final Edit<S>... edits) {
    return Fusion.fuse(edits) != null;
  }

  @SafeVarargs
  private static <S> S sequential(final S source, final Edit<S>... edits) {
    var s = source;
    for (final var e : edits) s = e.apply(s);
    return s;
  }

  @Test
  @DisplayName("an @Data navigator fuses with a hand-written bean path and matches the sequential fold")
  void dataNavigatorFusesWithHandWrittenAndMatchesSequential() {
    final var nav = DataUserTelescope.of();
    assertNotNull(nav.email().hops);
    final var e1 = over(nav.email(), (final String s) -> s + "1");
    final var e2 = over(Telescope.ofBean(DataUser.class).field(DataUser::getEmail), String::toUpperCase);
    final var id = over(nav.id(), (final String s) -> s + "!");
    final var src = new DataUser("u", "a@x");
    assertTrue(fuses(e1, id, e2));
    assertEquals(sequential(src, e1, id, e2), Telescope.all(e1, id, e2).apply(src));
    assertEquals("A@X1", Telescope.all(e1, id, e2).apply(src).getEmail());
  }

  @Test
  @DisplayName("a @Builder navigator fuses with a hand-written bean path and keeps edit order")
  void builderNavigatorFusesAndMatchesSequential() {
    final var nav = BuilderUserTelescope.of();
    final var e1 = over(nav.email(), (final String s) -> s + "1");
    final var e2 = over(Telescope.ofBean(BuilderUser.class).field(BuilderUser::getEmail), String::toUpperCase);
    final var id = over(nav.id(), (final String s) -> s + "!");
    assertTrue(fuses(e2, id, e1));
    final var out = Telescope.all(e2, id, e1).apply(BuilderUser.builder().id("u").email("a@x").build());
    assertEquals("A@X1", out.getEmail());
    assertEquals("u!", out.getId());
  }
}
