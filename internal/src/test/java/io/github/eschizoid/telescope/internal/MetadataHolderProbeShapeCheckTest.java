package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link MetadataHolderProbe}'s shape-check error branches AND the bound-constructor payoff
 * (Phase B's main outcome, which the original test exercised only by inspection of {@code
 * constantsByName}). Each hand-rolled fixture simulates a specific codegen-out-of-sync scenario; a
 * regression that swallowed or weakened any of these diagnostics would surface here as a failing
 * assertion rather than a cryptic deep-stack-trace at first adopter use.
 */
class MetadataHolderProbeShapeCheckTest {

  @Nested
  @DisplayName("Bound constructor — Phase B's runtime payoff is verified end-to-end")
  class BoundConstructor {

    @Test
    @DisplayName(
      "HolderRef.constructor() round-trips a sibling FieldOptics's construct(Function) via LambdaMetafactory"
    )
    void constructorRoundTrip() {
      // The Phase B contract: probeFor returns a HolderRef whose `constructor` is a cached
      // Function<Function<String, Object>, Object> bound to the codegen-emitted construct(...) via
      // LambdaMetafactory. Existing MetadataHolderProbeTest never invokes constructor() —
      // a regression to per-call reflection (or a null `constructor` field) would not be caught.
      // Here we drive it directly and assert the rebuilt instance matches the input.
      final var probe = MetadataHolderProbe.probeFor(ProbedRecord.class);
      assertTrue(probe.isPresent(), "ProbedRecord must have a sibling FieldOptics holder");
      final var holder = probe.get();
      assertNotNull(holder.constructor(), "constructor must be bound — Phase B's main payoff");

      final Function<String, Object> values = name ->
        switch (name) {
          case "name" -> "alice";
          case "age" -> 42;
          default -> null;
        };
      final var result = holder.constructor().apply(values);
      assertSame(ProbedRecord.class, result.getClass(), "bound constructor must produce a ProbedRecord");
      final var rebuilt = (ProbedRecord) result;
      assertSame("alice", rebuilt.name());
      // The construct(Function) shape mirrors the canonical-ctor call — every component flows
      // through the values function.
    }
  }

  @Nested
  @DisplayName("Shape-check error branches — precise diagnostics for codegen-out-of-sync scenarios")
  class ShapeCheck {

    @Test
    @DisplayName("Missing constants() method throws IllegalStateException naming the missing method + re-run hint")
    void missingConstantsMethod() {
      // Real scenario: adopter upgrades the runtime but their build cache still has Phase A
      // FieldOptics from an older codegen that didn't emit constants(). The diagnostic must be
      // precise — "missing the required `public static Map<String, Object> constants()` method.
      // Re-run the @Focus / @BeanFocus processor." — so the adopter knows exactly what to do.
      final var ex = assertThrows(IllegalStateException.class, () ->
        MetadataHolderProbe.probeFor(HolderMissingConstants.class)
      );
      assertTrue(
        ex.getMessage().contains("missing the required"),
        () -> "missing 'missing the required' hint: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("constants()"),
        () -> "error must name the missing method: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("Re-run the @Focus"),
        () -> "error must include re-run hint: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName("Non-static constants() throws IllegalStateException with 'shape is wrong' diagnostic")
    void nonStaticConstantsMethod() {
      // A misshapen `constants()` (non-static, wrong return type) is a different codegen bug from
      // missing-method — pin the separate branch so a refactor that merged them silently loses the
      // diagnostic granularity adopters need to triage.
      final var ex = assertThrows(IllegalStateException.class, () ->
        MetadataHolderProbe.probeFor(HolderNonStaticConstants.class)
      );
      assertTrue(
        ex.getMessage().contains("shape is wrong"),
        () -> "missing 'shape is wrong' diagnostic: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("public static Map"),
        () -> "error must state the expected shape: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName("Missing construct(Function) throws IllegalStateException naming the target class")
    void missingConstructMethod() {
      // The construct(Function) shape carries the target class in the diagnostic (its simple name)
      // so the adopter sees which type's codegen is out of sync. Pin both the missing-method
      // phrasing AND the target class name appearing in the message.
      final var ex = assertThrows(IllegalStateException.class, () ->
        MetadataHolderProbe.probeFor(HolderMissingConstruct.class)
      );
      assertTrue(
        ex.getMessage().contains("construct(Function<String, Object>)"),
        () -> "error must name the missing method: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("HolderMissingConstruct"),
        () -> "error must include the target class name: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName("construct(Function) returning the wrong type is rejected at probe time, not at first use")
    void wrongConstructReturnType() {
      // The assignability check at line 167 of MetadataHolderProbe catches a malformed construct
      // (returns Object instead of Target). Without it, the LMF bind succeeds but adopters would
      // get a ClassCastException at first use — opaque stack trace, no hint at the codegen bug.
      // Pinning the plan-time rejection guards against a future "trust the codegen" simplification
      // that drops the check.
      final var ex = assertThrows(IllegalStateException.class, () ->
        MetadataHolderProbe.probeFor(HolderWrongConstructReturn.class)
      );
      assertTrue(
        ex.getMessage().contains("shape is wrong"),
        () -> "missing 'shape is wrong' diagnostic: " + ex.getMessage()
      );
      assertTrue(
        ex.getMessage().contains("HolderWrongConstructReturn"),
        () -> "error must include the target class name: " + ex.getMessage()
      );
    }
  }
}
