package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The words both paths refuse a {@code when(...)} row and a merge in, and fail them with. The
 * runtime mapper and the generated bridge each read them from here, so the text is pinned once.
 */
class WhenAndMergeMessagesTest {

  @Test
  @DisplayName("a nested when is refused with the way to combine the predicates")
  void aNestedWhenNamesHowToCombine() {
    assertEquals(
      "Mapping.when(...) does not nest. Combine predicates with Predicate#and / Predicate#or on a single when(...)" +
        " row instead of stacking when(when(...)).",
      PairingMessages.whenNested()
    );
  }

  @Test
  @DisplayName("a when around a flat row names its kind and accessors and points at toOrElse")
  void aWhenAroundAFlatRowPointsAtToOrElse() {
    assertEquals(
      "Mapping.when(...) wraps only telescope-based rows: to(srcAcc, targetTelescope), to(srcTelescope, tgtAcc)," +
        " to(srcTelescope, tgtTelescope), zip(srcTelescope, tgtTelescope), constant(targetTelescope, value)," +
        " compute(targetTelescope, supplier). For field-level predicate gating on flat accessors, use" +
        " Mapping.toOrElse(src, tgt, defaultValue, predicate) — the source-value predicate applies the default" +
        " when matched. Got inner row of kind SameTypedTo (a → c).",
      PairingMessages.whenWrapsOnly("SameTypedTo", "a", "c")
    );
  }

  @Test
  @DisplayName("a when around a one-way row says to fold the predicate into its function")
  void aWhenAroundAOneWayRowSaysToFoldThePredicate() {
    assertEquals(
      "Mapping.when(...) wraps only telescope-based rows: to(srcAcc, targetTelescope), to(srcTelescope, tgtAcc)," +
        " to(srcTelescope, tgtTelescope), zip(srcTelescope, tgtTelescope), constant(targetTelescope, value)," +
        " compute(targetTelescope, supplier). Mapping.toOneWay(src, tgt, fn) rows cannot be predicate-gated" +
        " externally — fold the predicate into the forward function (return the source value or a default based" +
        " on the predicate). Got inner row of kind ForwardOnlyTransformTo (a → c).",
      PairingMessages.whenWrapsOnly("ForwardOnlyTransformTo", "a", "c")
    );
  }

  @Test
  @DisplayName("a when around a row naming no accessor, or one, says so in its place")
  void aWhenAroundARowNamingFewerAccessors() {
    final var drop = PairingMessages.whenWrapsOnly("Drop", "secret", null);
    assertEquals("Got inner row of kind Drop (secret → <telescope>).", drop.substring(drop.indexOf("Got inner")));
    final var none = PairingMessages.whenWrapsOnly("Zip", null, null);
    assertEquals("Got inner row of kind Zip.", none.substring(none.indexOf("Got inner")));
  }

  @Test
  @DisplayName("a predicate that throws is reported by the row it gates and the failure's class and message")
  void aThrowingPredicateIsReported() {
    assertEquals(
      "Mapping.when(...) predicate threw — inner=TelescopeTo (sourceField=nick). Predicate failure:" +
        " IllegalArgumentException: no",
      PairingMessages.whenPredicateThrew("TelescopeTo", "nick", new IllegalArgumentException("no"))
    );
    assertEquals(
      "Mapping.when(...) predicate threw — inner=Constant (sourceField=<telescope>). Predicate failure:" +
        " NullPointerException",
      PairingMessages.whenPredicateThrew("Constant", null, new NullPointerException())
    );
  }

  @Test
  @DisplayName("a merge says it converts forward only")
  void aMergeIsForwardOnly() {
    assertEquals(
      "Telescope.merge produces a forward-only mapper — the multi-source case has no general inverse. Use" +
        " Mapper.forward(...) only; backward/patch are unsupported.",
      PairingMessages.mergeForwardOnly()
    );
  }

  @Test
  @DisplayName("a missing merge source is named with the row needing it and the sources given")
  void aMissingMergeSourceIsNamed() {
    assertEquals(
      "Telescope.merge: forward called without a source for class a.Audit (required by the row writing target" +
        " field 'by'). Sources bag contains: [Customer, Line]. Pass a value of Audit via Sources.of(...) or" +
        " Sources.builder().with(...).",
      PairingMessages.mergeSourceMissing("a.Audit", "Audit", "by", List.of("Customer", "Line"))
    );
  }

  @Test
  @DisplayName("a merge row naming no component, one claiming a component twice, and a backfill of two types")
  void theMergeRowRefusals() {
    assertEquals(
      "Telescope.merge: step at index 2 targets 'toString', which is not a settable component/property of" +
        " Profile. Known fields: [id, by].",
      PairingMessages.mergeNotATarget(2, "toString", "Profile", List.of("id", "by"))
    );
    assertEquals(
      "Telescope.merge: step at index 1 writes target field 'by' which an earlier step already claimed. Each" +
        " target component may be written by at most one row.",
      PairingMessages.mergeClaimedTwice(1, "by")
    );
    assertEquals(
      "Telescope.merge: step at index 0 auto(Customer) matched field 'at' by name but types differ:" +
        " source=class java.lang.String, target=long. Pre-convert the value into a matching-typed holder record," +
        " or rename to break the name match if this row was unintentional.",
      PairingMessages.mergeAutoTypesDiffer(0, "Customer", "at", "class java.lang.String", "long")
    );
  }
}
