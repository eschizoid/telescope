package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the {@link BridgeHolderProbe} substrate — the malformed-holder diagnostic
 * branches and the per-{@code (source, target)} cache identity. The probe's end-to-end behaviour
 * through {@code Telescope.mapperForward} is covered in {@code :core}'s {@code
 * MigrationRegressionTest}; these tests exercise the failure modes the public path can't reach with
 * real {@code @Bridge}-generated fixtures.
 */
class BridgeHolderProbeTest {

  @Nested
  @DisplayName("Malformed-holder diagnostics — codegen drift surfaces a precise message")
  class MalformedHolders {

    /** Source class whose sibling probe finds a holder missing the BRIDGE field entirely. */
    public static final class MissingBridgeFieldSrc {}

    public static final class MissingBridgeFieldSrcBridge {
      // No BRIDGE field at all — pins the NoSuchFieldException branch.
    }

    @Test
    @DisplayName("holder exists but has no BRIDGE field → IllegalStateException naming the offending class")
    void missingBridgeFieldThrows() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        BridgeHolderProbe.probeFor(MissingBridgeFieldSrc.class, String.class)
      );
      assertTrue(
        ex.getMessage().contains(MissingBridgeFieldSrcBridge.class.getName()),
        () -> "diagnostic must name the malformed holder; got: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("Re-run the @Bridge processor"),
        () -> "diagnostic must actionably suggest re-running codegen; got: " + ex.getMessage()
      );
    }

    /** Source whose holder has a BRIDGE field with the wrong modifiers (not final). */
    public static final class NonFinalBridgeFieldSrc {}

    public static final class NonFinalBridgeFieldSrcBridge {

      // Public + static but NOT final — pins the modifier-validation branch.
      public static Object BRIDGE = null;
    }

    @Test
    @DisplayName("BRIDGE field with wrong modifiers (missing final) → IllegalStateException")
    void wrongModifiersThrows() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        BridgeHolderProbe.probeFor(NonFinalBridgeFieldSrc.class, String.class)
      );
      assertTrue(
        ex.getMessage().contains("public static final"),
        () -> "diagnostic must explain the required modifier shape; got: " + ex.getMessage()
      );
    }

    /** Source whose holder has a null BRIDGE constant. */
    public static final class NullBridgeFieldSrc {}

    public static final class NullBridgeFieldSrcBridge {

      // Public + static + final but null — pins the null-value branch in readBridgeConstant.
      public static final Object BRIDGE = null;
    }

    @Test
    @DisplayName("BRIDGE field present but value is null → IllegalStateException naming the malformed-codegen cause")
    void nullBridgeValueThrows() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        BridgeHolderProbe.probeFor(NullBridgeFieldSrc.class, String.class)
      );
      assertTrue(
        ex.getMessage().contains("null BRIDGE constant"),
        () -> "diagnostic must call out the null value specifically; got: " + ex.getMessage()
      );
    }
  }

  @Nested
  @DisplayName("Cache identity — repeated probeFor calls return the same BridgeRef instance")
  class CacheIdentity {

    public static final class CacheSrc {}

    public static final class CacheSrcBridge {

      public static final String BRIDGE = "test-bridge-marker";
    }

    @Test
    @DisplayName("probeFor on a present holder caches the BridgeRef — repeated calls return the same instance")
    void repeatedProbeReturnsSameRef() {
      // Presence and absence must both be memoised per (source, target); assertSame pins the
      // cached BridgeRef reference, distinguishing memoised hits from rebuilds that produce
      // equivalent-but-fresh instances.
      final var first = BridgeHolderProbe.probeFor(CacheSrc.class, String.class);
      final var second = BridgeHolderProbe.probeFor(CacheSrc.class, String.class);
      assertTrue(first.isPresent());
      assertTrue(second.isPresent());
      assertSame(first.get(), second.get(), "cache must return the same BridgeRef instance");
      assertEquals("test-bridge-marker", first.get().bridge());
    }

    public static final class CacheEmptySrc {}

    @Test
    @DisplayName("probeFor on a missing holder caches Optional.empty — no repeated Class.forName")
    void absenceIsMemoised() {
      // CacheEmptySrc has NO sibling Bridge class — the probe returns empty and caches that. A
      // regression that didn't cache absence would fall back to Class.forName on every call, which
      // is still functional but defeats the purpose of the cache. assertSame on the Optional
      // instance pins the memoised reference.
      final var first = BridgeHolderProbe.probeFor(CacheEmptySrc.class, String.class);
      final var second = BridgeHolderProbe.probeFor(CacheEmptySrc.class, String.class);
      assertTrue(first.isEmpty());
      assertSame(first, second, "absence Optional must be memoised, not re-computed");
    }
  }

  @Nested
  @DisplayName("Lookup order — long-form <Source>To<Target>Bridge is tried before short-form")
  class LookupOrder {

    public static final class LongFormSrc {}

    public static final class LongFormSrcToStringBridge {

      public static final String BRIDGE = "long-form-marker";
    }

    public static final class LongFormSrcBridge {

      public static final String BRIDGE = "short-form-marker";
    }

    @Test
    @DisplayName("when both long-form and short-form holders exist, the long-form value wins")
    void longFormTakesPrecedence() {
      // Pins the lookup-order contract documented on probe(): the long-form FQN is tried first
      // so a source carrying multiple bridges resolves unambiguously to the one targeting the
      // requested class.
      final var probed = BridgeHolderProbe.probeFor(LongFormSrc.class, String.class);
      assertTrue(probed.isPresent(), "long-form holder must be discovered");
      assertEquals("long-form-marker", probed.get().bridge(), "long-form value must win over short-form");
    }
  }
}
