package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the deep-mapping forward branch when both sides carry sibling {@code
 * <X>Telescope} metadata holders with the {@code construct(Function<String, Object>)} method. The
 * engine's structural-iso map-to-instance branch routes through the holder's pre-baked constructor
 * instead of the reflective {@code Records.construct} / {@code Beans.BeanWriter} path.
 *
 * <p>The tests in {@link DeepMapPhaseCHolderTest} cover the backward branch (instance decomposition
 * through holder lenses). Together they eliminate the per-component reflective dispatch on both
 * sides of a holder-covered type pair.
 *
 * <p>Reuses the {@link PhaseCSrc} / {@link PhaseCDst} / {@link PhaseCPlainSrc} / {@link
 * PhaseCPlainDst} fixtures — the same holders, observed from a different angle (forward construct
 * vs backward decompose). The annotated fixtures carry the {@code construct(...)} method since
 * {@code FocusProcessor} emits it alongside the per-component lens constants.
 */
class DeepMapPhaseDConstructTest {

  @Test
  @DisplayName("both sides annotated: forward round-trips through the holder-bound constructor")
  void bothSidesAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCSrc.class, PhaseCDst.class);
    final var src = new PhaseCSrc("dave", 41);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCDst("dave", 41), dst, "forward map must produce a same-valued target via holder construct");
    assertEquals(src, mapper.backward(dst), "backward map must recover the source byte-for-byte via holder construct");
  }

  @Test
  @DisplayName("both sides annotated: holders expose a bound constructor (defends against codegen drift)")
  void bothSidesHaveBoundConstructors() {
    final var srcHolder = MetadataHolderProbe.probeFor(PhaseCSrc.class);
    final var dstHolder = MetadataHolderProbe.probeFor(PhaseCDst.class);
    assertTrue(srcHolder.isPresent(), "PhaseCSrc must have a sibling PhaseCSrcTelescope holder on the classpath");
    assertTrue(dstHolder.isPresent(), "PhaseCDst must have a sibling PhaseCDstTelescope holder on the classpath");
    assertNotNull(
      srcHolder.get().constructor(),
      "PhaseCSrcTelescope must expose a construct(Function) method bound at probe time"
    );
    assertNotNull(
      dstHolder.get().constructor(),
      "PhaseCDstTelescope must expose a construct(Function) method bound at probe time"
    );
  }

  @Test
  @DisplayName("holder-bound constructor builds the right instance from a name-keyed function")
  void holderConstructorBuildsCorrectInstance() {
    final var holder = MetadataHolderProbe.probeFor(PhaseCSrc.class).orElseThrow();
    final var constructor = holder.constructor();
    assertNotNull(constructor, "PhaseCSrcTelescope must expose a construct(Function) method");
    final var built = constructor.apply(name ->
      switch (name) {
        case "name" -> "erin";
        case "age" -> 52;
        default -> throw new IllegalArgumentException("Unexpected component: " + name);
      }
    );
    assertEquals(
      new PhaseCSrc("erin", 52),
      built,
      "holder-bound constructor must call the canonical constructor with the right components"
    );
  }

  @Test
  @DisplayName("neither side annotated: the reflective construct fallback still round-trips")
  void neitherSideAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCPlainSrc.class, PhaseCPlainDst.class);
    final var src = new PhaseCPlainSrc("frank", 19);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCPlainDst("frank", 19), dst, "fallback forward map must produce a same-valued target");
    assertEquals(src, mapper.backward(dst), "fallback backward map must recover the source byte-for-byte");

    assertTrue(
      MetadataHolderProbe.probeFor(PhaseCPlainSrc.class).isEmpty(),
      "PhaseCPlainSrc should have no sibling holder — the fixture is the no-holder control"
    );
    assertTrue(
      MetadataHolderProbe.probeFor(PhaseCPlainDst.class).isEmpty(),
      "PhaseCPlainDst should have no sibling holder — the fixture is the no-holder control"
    );
  }

  @Test
  @DisplayName(
    "mixed: one side annotated, the other plain — holder construct used on annotated side, fallback on plain"
  )
  void mixedAnnotatedAndPlainRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCPlainSrc.class, PhaseCDst.class);
    final var src = new PhaseCPlainSrc("gina", 33);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCDst("gina", 33), dst, "mixed forward map must produce a same-valued target");
    assertEquals(src, mapper.backward(dst), "mixed backward map must recover the source byte-for-byte");
  }
}
