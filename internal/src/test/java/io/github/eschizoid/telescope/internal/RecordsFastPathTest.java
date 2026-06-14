package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Records.fastPathForward(srcClass, tgtClass)} — the Tier-A runtime fast-path
 * eligibility rules + correctness on the records → records path.
 *
 * <p>Eligibility: both records, same-name + EXACTLY same-type components, all components are
 * flat scalars (primitive / primitive wrapper / String / enum). Anything else returns {@code
 * null} so the caller falls back to the slow path that preserves deep-copy / lift / cycle
 * semantics.
 */
class RecordsFastPathTest {

  enum Status {
    ACTIVE,
    INACTIVE,
  }

  record Flat(long id, String name, int age, boolean active, Status status) {}

  record FlatSame(long id, String name, int age, boolean active, Status status) {}

  @Nested
  @DisplayName("Eligible — both records, same flat scalar shape")
  class Eligible {

    @Test
    @DisplayName("returns a Function; forward maps every field correctly")
    void roundTripAllScalars() {
      final var fn = Records.fastPathForward(Flat.class, FlatSame.class);
      assertNotNull(fn);

      final var src = new Flat(42L, "Alice", 30, true, Status.ACTIVE);
      final var dst = fn.apply(src);

      assertEquals(new FlatSame(42L, "Alice", 30, true, Status.ACTIVE), dst);
    }

    @Test
    @DisplayName("null source short-circuits to null (no NPE on the canonical ctor invocation)")
    void nullSource() {
      final var fn = Records.fastPathForward(Flat.class, FlatSame.class);
      assertNotNull(fn);
      assertNull(fn.apply(null));
    }

    @Test
    @DisplayName("cache returns same Function on repeated lookups for the same pair")
    void cacheReturnsSameFunction() {
      final var first = Records.fastPathForward(Flat.class, FlatSame.class);
      final var second = Records.fastPathForward(Flat.class, FlatSame.class);
      assertSame(first, second, "fast-path Function is cached per (src, tgt) pair");
    }

    @Test
    @DisplayName("identity Flat → Flat is eligible (same class as src and tgt)")
    void identityRoundTrip() {
      final var fn = Records.fastPathForward(Flat.class, Flat.class);
      assertNotNull(fn);

      final var src = new Flat(1L, "x", 0, false, Status.INACTIVE);
      assertEquals(src, fn.apply(src));
    }
  }

  @Nested
  @DisplayName("Not eligible — falls back to slow path (returns null)")
  class NotEligible {

    record FlatRenamed(long id, String fullName, int age, boolean active, Status status) {}

    record FlatTypeMismatch(Long id, String name, int age, boolean active, Status status) {}

    record WithList(long id, List<String> tags) {}

    record WithOptional(long id, Optional<String> nickname) {}

    record NestedOuter(long id, Flat inner) {}

    record NestedOuterSame(long id, FlatSame inner) {}

    static class FlatBean {

      public long id;
      public String name;
    }

    @Test
    @DisplayName("different field NAME on target → null")
    void differentFieldName() {
      assertNull(Records.fastPathForward(Flat.class, FlatRenamed.class));
    }

    @Test
    @DisplayName("different field TYPE (long → Long is still mismatch) → null")
    void differentFieldType() {
      assertNull(Records.fastPathForward(Flat.class, FlatTypeMismatch.class));
    }

    @Test
    @DisplayName("source is a bean (non-record) → null")
    void sourceNotARecord() {
      assertNull(Records.fastPathForward(FlatBean.class, Flat.class));
    }

    @Test
    @DisplayName("target is a bean (non-record) → null")
    void targetNotARecord() {
      assertNull(Records.fastPathForward(Flat.class, FlatBean.class));
    }

    @Test
    @DisplayName("container component (List) → null (deep-copy / lift goes through slow path)")
    void containerComponent() {
      record SrcWithList(long id, List<String> tags) {}
      record TgtWithList(long id, List<String> tags) {}
      assertNull(Records.fastPathForward(SrcWithList.class, TgtWithList.class));
    }

    @Test
    @DisplayName("Optional component → null (Optional is not in the flat-scalar whitelist)")
    void optionalComponent() {
      assertNull(Records.fastPathForward(WithOptional.class, WithOptional.class));
    }

    @Test
    @DisplayName("nested record component → null (cycle detection + deep copy goes through slow path)")
    void nestedRecordComponent() {
      assertNull(Records.fastPathForward(NestedOuter.class, NestedOuterSame.class));
    }
  }

  @Nested
  @DisplayName("Cache behaviour — non-eligible entries also cached (sentinel) to short-circuit re-checks")
  class CachingBehavior {

    record NoMatchSrc(String name) {}

    record NoMatchTgt(int age) {}

    @Test
    @DisplayName("repeated non-eligible lookups all return null without re-running eligibility scan")
    void cachesNonEligibleAsSentinel() {
      // First call computes eligibility and caches the sentinel; subsequent calls hit cache.
      // We can only assert NULL is returned consistently; the cache-hit fast path is internal.
      assertNull(Records.fastPathForward(NoMatchSrc.class, NoMatchTgt.class));
      assertNull(Records.fastPathForward(NoMatchSrc.class, NoMatchTgt.class));
      assertNull(Records.fastPathForward(NoMatchSrc.class, NoMatchTgt.class));
    }
  }
}
