package io.github.eschizoid.telescope.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for ADR-0006 Phase C: when both sides of a {@link Telescope#map(Class, Class,
 * MapStep...)} call carry sibling {@code <X>Telescope} metadata holders, the deep-mapping engine's
 * structural-iso instance-to-map decomposition routes through the holders' pre-baked {@code Lens}
 * constants instead of the per-component {@code Records.read} dispatch.
 *
 * <p>Behavioural parity is the load-bearing assertion — the user sees identical {@code to} / {@code
 * from} results regardless of which dispatch path served the per-component reads. A second case
 * exercises the no-holder fallback by mapping between unannotated records.
 */
class DeepMapPhaseCHolderTest {

  @Test
  @DisplayName("both sides annotated: Telescope.map round-trips through the holder-driven structural iso")
  void bothSidesAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCSrc.class, PhaseCDst.class);
    final var src = new PhaseCSrc("alice", 30);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCDst("alice", 30), dst, "forward map must produce a same-valued target record");
    assertEquals(src, mapper.backward(dst), "backward map must recover the source byte-for-byte");
  }

  @Test
  @DisplayName("both sides annotated: holders are actually present (defends against fixture/codegen drift)")
  void bothSidesHaveHolders() {
    assertTrue(
      MetadataHolderProbe.probeFor(PhaseCSrc.class).isPresent(),
      "PhaseCSrc must have a sibling PhaseCSrcTelescope holder on the classpath"
    );
    assertTrue(
      MetadataHolderProbe.probeFor(PhaseCDst.class).isPresent(),
      "PhaseCDst must have a sibling PhaseCDstTelescope holder on the classpath"
    );
  }

  @Test
  @DisplayName("neither side annotated: the structural-iso reflective fallback still round-trips")
  void neitherSideAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCPlainSrc.class, PhaseCPlainDst.class);
    final var src = new PhaseCPlainSrc("bob", 42);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCPlainDst("bob", 42), dst, "fallback forward map must produce a same-valued target");
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
  @DisplayName("mixed: one side annotated, the other plain — holder used on annotated side, fallback on plain side")
  void mixedAnnotatedAndPlainRoundTrip() {
    final var mapper = Telescope.mapper(PhaseCSrc.class, PhaseCPlainDst.class);
    final var src = new PhaseCSrc("carol", 27);
    final var dst = mapper.forward(src);
    assertEquals(new PhaseCPlainDst("carol", 27), dst, "mixed forward map must produce a same-valued target");
    assertEquals(src, mapper.backward(dst), "mixed backward map must recover the source byte-for-byte");
  }
}
