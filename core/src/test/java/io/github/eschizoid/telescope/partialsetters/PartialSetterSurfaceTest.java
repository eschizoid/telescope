package io.github.eschizoid.telescope.partialsetters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Writing one property rebuilds the whole bean, so the surface chosen to rebuild through decides
 * what survives. A setter surface that cannot reach every property skips the ones it cannot write
 * and leaves them at their defaults — acceptable where nothing else is on offer, and not where a
 * builder can carry them.
 *
 * <p>{@link PartialBean} is what makes this able to fail: its {@code code} has a builder method and
 * no setter. Against a bean whose setters cover everything, both surfaces reproduce the same value
 * and the assertion below holds whichever is chosen.
 */
class PartialSetterSurfaceTest {

  private static PartialBean sample() {
    return PartialBean.builder().name("alice").code("AB").build();
  }

  @Test
  @DisplayName("writing one property does not drop another that only the builder can reach")
  void unsettablePropertySurvivesAWrite() {
    final var written = Telescope.ofBean(PartialBean.class).field(PartialBean::getName).set(sample(), "bob");

    assertEquals("bob", written.getName(), "the written property lands");
    assertEquals("AB", written.getCode(), "and the one with no setter is carried, not dropped");
  }

  @Test
  @DisplayName("an update rebuilds the same way")
  void unsettablePropertySurvivesAnUpdate() {
    final var updated = Telescope.ofBean(PartialBean.class)
      .field(PartialBean::getName)
      .update(sample(), String::toUpperCase);

    assertEquals("ALICE", updated.getName());
    assertEquals("AB", updated.getCode());
  }

  @Test
  @DisplayName("reading is unaffected, which is what makes the loss quiet")
  void readIsUnaffected() {
    // The control. A read never rebuilds, so it reports the property present whichever surface a
    // write would have used — a suite that only read would see nothing wrong.
    assertEquals("AB", Telescope.ofBean(PartialBean.class).field(PartialBean::getCode).read(sample()));
  }
}
