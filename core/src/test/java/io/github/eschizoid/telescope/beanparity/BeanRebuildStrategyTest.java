package io.github.eschizoid.telescope.beanparity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bean offering both a builder and setters can be rebuilt two ways, and the two do not have to
 * agree: a builder normalises, defaults and validates on {@code build()}, while a setter stores
 * what it was handed. The generated metadata holder is an optimisation of the reflective path, so
 * the two must choose the same surface — otherwise the same call returns a different value
 * depending on whether the holder was loadable, with nothing raised either way.
 *
 * <p>{@link NormalisingBean}'s builder upper-cases its code. That is what makes these able to fail:
 * against a bean whose builder merely copies, both strategies produce the same value and every
 * assertion below would hold whichever was chosen.
 */
class BeanRebuildStrategyTest {

  private static NormalisingBean sample() {
    final var bean = new NormalisingBean();
    bean.setName("alice");
    bean.setCode("ab");
    return bean;
  }

  @Test
  @DisplayName("a write lands through the setter, so the builder's normalisation is not applied")
  void writeUsesTheSetterSurface() {
    final var written = Telescope.ofBean(NormalisingBean.class).field(NormalisingBean::getCode).set(sample(), "xy");

    assertEquals("xy", written.getCode(), "routed through build() this would be XY");
    assertEquals("alice", written.getName(), "the off-path property survives the rebuild");
  }

  @Test
  @DisplayName("an update lands the same way, since it rebuilds through the same surface")
  void updateUsesTheSetterSurface() {
    final var updated = Telescope.ofBean(NormalisingBean.class)
      .field(NormalisingBean::getCode)
      .update(sample(), c -> c + "z");

    assertEquals("abz", updated.getCode(), "routed through build() this would be ABZ");
  }

  @Test
  @DisplayName("reading is unaffected, which is what makes the write divergence quiet")
  void readIsUnaffected() {
    // The control. A read never rebuilds, so it agrees whichever surface a write would pick — and
    // a suite that only read would report the two paths as matching.
    assertEquals("ab", Telescope.ofBean(NormalisingBean.class).field(NormalisingBean::getCode).read(sample()));
  }
}
