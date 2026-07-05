package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the deep-mapping backward branch: when both sides of a {@code
 * Telescope.map(Class, Class, MapStep...)} call carry sibling {@code <X>Telescope} metadata
 * holders, the engine's structural-iso instance-to-map decomposition routes through the holders'
 * pre-baked {@code Lens} constants instead of the per-component {@code Records.read} dispatch.
 *
 * <p>Behavioural parity is the load-bearing assertion — the user sees identical {@code to} / {@code
 * from} results regardless of which dispatch path served the per-component reads. A second case
 * exercises the no-holder fallback by mapping between unannotated records.
 */
class DeepMapHolderIsoReadTest {

  @Test
  @DisplayName("both sides annotated: Telescope.map round-trips through the holder-driven structural iso")
  void bothSidesAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(HolderIsoSrc.class, HolderIsoDst.class);
    final var src = new HolderIsoSrc("alice", 30);
    final var dst = mapper.forward(src);
    assertEquals(new HolderIsoDst("alice", 30), dst, "forward map must produce a same-valued target record");
    assertEquals(src, mapper.backward(dst), "backward map must recover the source byte-for-byte");
  }

  @Test
  @DisplayName("both sides annotated: holders are actually present (defends against fixture/codegen drift)")
  void bothSidesHaveHolders() {
    assertTrue(
      MetadataHolderProbe.probeFor(HolderIsoSrc.class).isPresent(),
      "HolderIsoSrc must have a sibling HolderIsoSrcTelescope holder on the classpath"
    );
    assertTrue(
      MetadataHolderProbe.probeFor(HolderIsoDst.class).isPresent(),
      "HolderIsoDst must have a sibling HolderIsoDstTelescope holder on the classpath"
    );
  }

  @Test
  @DisplayName("neither side annotated: the structural-iso reflective fallback still round-trips")
  void neitherSideAnnotatedRoundTrip() {
    final var mapper = Telescope.mapper(HolderIsoPlainSrc.class, HolderIsoPlainDst.class);
    final var src = new HolderIsoPlainSrc("bob", 42);
    final var dst = mapper.forward(src);
    assertEquals(new HolderIsoPlainDst("bob", 42), dst, "fallback forward map must produce a same-valued target");
    assertEquals(src, mapper.backward(dst), "fallback backward map must recover the source byte-for-byte");

    assertTrue(
      MetadataHolderProbe.probeFor(HolderIsoPlainSrc.class).isEmpty(),
      "HolderIsoPlainSrc should have no sibling holder — the fixture is the no-holder control"
    );
    assertTrue(
      MetadataHolderProbe.probeFor(HolderIsoPlainDst.class).isEmpty(),
      "HolderIsoPlainDst should have no sibling holder — the fixture is the no-holder control"
    );
  }

  @Test
  @DisplayName("mixed: one side annotated, the other plain — holder used on annotated side, fallback on plain side")
  void mixedAnnotatedAndPlainRoundTrip() {
    final var mapper = Telescope.mapper(HolderIsoSrc.class, HolderIsoPlainDst.class);
    final var src = new HolderIsoSrc("carol", 27);
    final var dst = mapper.forward(src);
    assertEquals(new HolderIsoPlainDst("carol", 27), dst, "mixed forward map must produce a same-valued target");
    assertEquals(src, mapper.backward(dst), "mixed backward map must recover the source byte-for-byte");
  }
}
