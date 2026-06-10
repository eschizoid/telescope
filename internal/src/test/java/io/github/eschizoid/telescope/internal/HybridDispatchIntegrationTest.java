package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test exercising ADR-0006 Phase B end-to-end: when an {@code @Focus}-annotated
 * record's call site dispatches via {@link Telescope#of(Class)} + {@link Telescope#field}, the
 * runtime probe finds the sibling {@code <X>Telescope} holder and routes through its pre-baked
 * {@link io.github.eschizoid.telescope.internal.optics.Lens Lens} constants instead of falling
 * through to {@link Records#fieldLens(String)}.
 *
 * <p>Behavioural parity is the load-bearing assertion — the user sees the same value regardless of
 * which dispatch path served the call. A second case exercises the no-holder fallback against a
 * record that doesn't carry {@code @Focus}.
 */
class HybridDispatchIntegrationTest {

  @Test
  @DisplayName("annotated record: Telescope.of(...).field(...) read returns the same value as direct accessor")
  void annotatedDispatchReadsSameValue() {
    final var alice = new HybridDispatchUser("alice", 30);
    final var name = Telescope.of(HybridDispatchUser.class).field(HybridDispatchUser::name);
    assertEquals("alice", name.read(alice), "holder-routed read must match the direct record accessor");
  }

  @Test
  @DisplayName(
    "annotated record: Telescope.of(...).field(...).update lifts a function over the leaf via the holder lens"
  )
  void annotatedDispatchUpdate() {
    final var alice = new HybridDispatchUser("alice", 30);
    final var renamed = Telescope.of(HybridDispatchUser.class)
      .field(HybridDispatchUser::name)
      .update(alice, String::toUpperCase);
    assertEquals("ALICE", renamed.name(), "the holder-routed update should rebuild the record with the new value");
    assertEquals(30, renamed.age(), "non-target components must carry over unchanged");
  }

  @Test
  @DisplayName("annotated record: int component is boxed and round-trips through the holder lens")
  void annotatedDispatchPrimitiveBoxing() {
    final var alice = new HybridDispatchUser("alice", 30);
    final var aged = Telescope.of(HybridDispatchUser.class)
      .field(HybridDispatchUser::age)
      .update(alice, n -> n + 1);
    assertEquals(31, aged.age(), "int component should box to Integer for the lens and round-trip cleanly");
    assertEquals("alice", aged.name(), "non-target components must carry over unchanged");
  }

  @Test
  @DisplayName(
    "unannotated record: Telescope.of(...).field(...) falls through to the reflective Records.fieldLens path"
  )
  void unannotatedDispatchFallsThroughToReflectivePath() {
    final var bob = new HybridDispatchPlainUser("bob", 42);
    final var renamed = Telescope.of(HybridDispatchPlainUser.class)
      .field(HybridDispatchPlainUser::name)
      .update(bob, String::toUpperCase);
    assertEquals("BOB", renamed.name(), "the reflective path must still work when no holder is on the classpath");
    assertEquals(42, renamed.age(), "non-target components must carry over unchanged");

    // Reaffirm the probe really did see this class as holder-less — defends against accidental
    // codegen on a fixture that isn't annotated.
    assertTrue(
      MetadataHolderProbe.probeFor(HybridDispatchPlainUser.class).isEmpty(),
      "HybridDispatchPlainUser should have no sibling <X>Telescope on the classpath"
    );
  }
}
