package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.DEFAULT;
import static io.github.eschizoid.telescope.mapping.NullHint.NullStrategy.PROPAGATE;
import static io.github.eschizoid.telescope.mapping.NullHint.nullSourceValues;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-mapper {@code NullHint.nullSourceValues(NullStrategy)} hint — the MapStruct {@code
 * nullValuePropertyMappingStrategy} equivalent for "null source values land as type defaults on the
 * target instead of as null." Covers the auto-recursion path, the SameTypedTo per-row path, the
 * per-row {@code toOrElse} override (must win over the global hint), and every scalar / container
 * leaf type {@code NullDefaults} substitutes.
 */
class NullStrategyTest {

  record Src(String name, Integer count, List<String> tags, Set<String> roles, Map<String, Integer> scores) {}

  record Dst(String name, Integer count, List<String> tags, Set<String> roles, Map<String, Integer> scores) {}

  @Nested
  @DisplayName("PROPAGATE — default behaviour: null source → null target (no hint required)")
  class Propagate {

    @Test
    @DisplayName("no hint → null source values land as null on target (v0.x behaviour)")
    void defaultIsPropagate() {
      final var mapper = Telescope.mapper(Src.class, Dst.class);

      final var allNull = mapper.forward(new Src(null, null, null, null, null));

      assertNull(allNull.name());
      assertNull(allNull.count());
      assertNull(allNull.tags());
      assertNull(allNull.roles());
      assertNull(allNull.scores());
    }

    @Test
    @DisplayName("explicit PROPAGATE hint matches the no-hint behaviour")
    void explicitPropagate() {
      final var mapper = Telescope.mapper(Src.class, Dst.class, nullSourceValues(PROPAGATE));

      assertNull(mapper.forward(new Src(null, null, null, null, null)).name());
    }
  }

  @Nested
  @DisplayName("DEFAULT — null source values substituted with NullDefaults#defaultFor target type")
  class DefaultStrategy {

    @Test
    @DisplayName("String → empty, Integer → 0, collections → empty singletons via auto-recursion")
    void scalarAndCollectionDefaults() {
      final var mapper = Telescope.mapper(Src.class, Dst.class, nullSourceValues(DEFAULT));

      final var defaulted = mapper.forward(new Src(null, null, null, null, null));

      assertEquals("", defaulted.name(), "String → \"\"");
      assertEquals(0, defaulted.count(), "Integer → 0");
      assertEquals(List.of(), defaulted.tags(), "List → empty singleton");
      assertEquals(Set.of(), defaulted.roles(), "Set → empty singleton");
      assertEquals(Map.of(), defaulted.scores(), "Map → empty singleton");
    }

    @Test
    @DisplayName("non-null sources unaffected by DEFAULT strategy")
    void nonNullSourcesPassThrough() {
      final var mapper = Telescope.mapper(Src.class, Dst.class, nullSourceValues(DEFAULT));

      final var loaded = new Src("Alice", 42, List.of("a", "b"), Set.of("admin"), Map.of("k", 1));
      assertEquals(new Dst("Alice", 42, List.of("a", "b"), Set.of("admin"), Map.of("k", 1)), mapper.forward(loaded));
    }

    @Test
    @DisplayName("mixed source — null and non-null fields each follow their own path")
    void mixedNullAndNonNull() {
      final var mapper = Telescope.mapper(Src.class, Dst.class, nullSourceValues(DEFAULT));

      final var mixed = mapper.forward(new Src("Alice", null, List.of("a"), null, null));

      assertEquals("Alice", mixed.name(), "non-null source passes through");
      assertEquals(0, mixed.count(), "null Integer → 0");
      assertEquals(List.of("a"), mixed.tags(), "non-null List passes through");
      assertEquals(Set.of(), mixed.roles(), "null Set → empty singleton");
      assertEquals(Map.of(), mixed.scores(), "null Map → empty singleton");
    }
  }

  @Nested
  @DisplayName("DEFAULT with SameTypedTo per-row mappings (same-name fields explicit overrides)")
  class SameTypedToWithDefault {

    record SrcRename(String givenName, Integer ageInYears) {}

    record DstRename(String fullName, Integer years) {}

    @Test
    @DisplayName("explicit to(srcAcc, tgtAcc) row honors DEFAULT strategy")
    void renameRowHonorsDefault() {
      final var mapper = Telescope.mapper(
        SrcRename.class,
        DstRename.class,
        nullSourceValues(DEFAULT),
        to(SrcRename::givenName, DstRename::fullName),
        to(SrcRename::ageInYears, DstRename::years)
      );

      final var defaulted = mapper.forward(new SrcRename(null, null));

      assertEquals("", defaulted.fullName(), "explicit rename row honors DEFAULT for String");
      assertEquals(0, defaulted.years(), "explicit rename row honors DEFAULT for Integer");
    }
  }

  @Nested
  @DisplayName("Per-row precedence — toOrElse beats per-mapper DEFAULT")
  class PerRowPrecedence {

    @Test
    @DisplayName("toOrElse(...) row wins over nullSourceValues(DEFAULT) — user's explicit default lands")
    void toOrElseBeatsGlobalDefault() {
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        nullSourceValues(DEFAULT),
        toOrElse(Src::name, Dst::name, "EXPLICIT-DEFAULT")
      );

      final var defaulted = mapper.forward(new Src(null, null, null, null, null));

      // name: toOrElse wins → "EXPLICIT-DEFAULT" (not "" from global DEFAULT)
      assertEquals("EXPLICIT-DEFAULT", defaulted.name(), "toOrElse user default wins over global DEFAULT");
      // count: no per-row override → global DEFAULT kicks in → 0
      assertEquals(0, defaulted.count(), "no per-row override → global DEFAULT");
    }
  }

  @Nested
  @DisplayName("Optional fields — DEFAULT substitutes Optional.empty() on null source")
  class OptionalFields {

    record OptSrc(Optional<String> nickname) {}

    record OptDst(Optional<String> nickname) {}

    @Test
    @DisplayName("null Optional source → Optional.empty() on target")
    void nullOptionalGetsEmpty() {
      final var mapper = Telescope.mapper(OptSrc.class, OptDst.class, nullSourceValues(DEFAULT));

      // Optional.empty() on the SOURCE is auto-recursed naturally to Optional.empty() on target.
      // The DEFAULT strategy applies when the source value itself is null (the Optional reference
      // is null) — substituting Optional.empty() on the target side.
      assertEquals(Optional.empty(), mapper.forward(new OptSrc(null)).nickname());
      assertEquals(Optional.of("a"), mapper.forward(new OptSrc(Optional.of("a"))).nickname());
    }
  }

  @Nested
  @DisplayName("Container-element nulls — DEFAULT applies at field level only, NOT per-element")
  class ContainerElementNulls {

    record TaggedSrc(List<String> tags) {}

    record TaggedDst(List<String> tags) {}

    @Test
    @DisplayName("null source list → empty target list; null elements INSIDE source list are preserved")
    void nullFieldVsNullElements() {
      final var mapper = Telescope.mapper(TaggedSrc.class, TaggedDst.class, nullSourceValues(DEFAULT));

      // Whole-field null → empty list (field-level DEFAULT)
      assertEquals(List.of(), mapper.forward(new TaggedSrc(null)).tags());

      // List containing a null element → null PRESERVED in target. NullStrategy.DEFAULT is a
      // field-level semantic (matches MapStruct SET_TO_DEFAULT); the gate sits at the field, not
      // inside the container. A regression that double-wrapped at element level would replace
      // the inner null with "" and break this assertion.
      final var withNullElement = new TaggedSrc(java.util.Arrays.asList("a", null, "c"));
      assertEquals(java.util.Arrays.asList("a", null, "c"), mapper.forward(withNullElement).tags());
    }
  }

  @Nested
  @DisplayName("Hint validation — duplicate nullSourceValues rejected at mapper build time")
  class HintValidation {

    @Test
    @DisplayName("two nullSourceValues hints throw IAE — at most one per mapper")
    void duplicateRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Src.class, Dst.class, nullSourceValues(DEFAULT), nullSourceValues(PROPAGATE))
      );
      assertTrue(ex.getMessage().contains("Duplicate nullSourceValues"));
    }
  }

  @Nested
  @DisplayName("Primitive numeric wrappers — DEFAULT substitutes JLS-style zeros")
  class PrimitiveWrappers {

    record AllNumeric(Integer i, Long l, Double d, Float f, Short sh, Byte b, Boolean bool) {}

    record AllNumericDst(Integer i, Long l, Double d, Float f, Short sh, Byte b, Boolean bool) {}

    @Test
    @DisplayName("Integer→0, Long→0L, Double→0.0, Float→0.0f, Short→0, Byte→0, Boolean→false")
    void allWrappersGetJlsDefaults() {
      final var mapper = Telescope.mapper(AllNumeric.class, AllNumericDst.class, nullSourceValues(DEFAULT));

      final var defaulted = mapper.forward(new AllNumeric(null, null, null, null, null, null, null));

      assertEquals(0, defaulted.i());
      assertEquals(0L, defaulted.l());
      assertEquals(0.0d, defaulted.d());
      assertEquals(0.0f, defaulted.f());
      assertEquals((short) 0, defaulted.sh());
      assertEquals((byte) 0, defaulted.b());
      assertEquals(false, defaulted.bool());
    }
  }

  @Nested
  @DisplayName("Backward direction unchanged — DEFAULT is forward-only")
  class BackwardUnchanged {

    @Test
    @DisplayName("mapper.backward(target) does NOT apply default substitution on null target fields")
    void backwardSkipsDefault() {
      final var mapper = Telescope.mapper(Src.class, Dst.class, nullSourceValues(DEFAULT));

      // Build a target with explicit nulls; backward direction reconstructs source.
      // The DEFAULT strategy is documented as forward-only, so null target fields rebuild as null
      // source fields (NOT as type defaults).
      final var withNulls = new Dst(null, null, null, null, null);
      final var s = mapper.backward(withNulls);

      assertNull(s.name(), "backward direction preserves null — DEFAULT is forward-only");
      assertNull(s.count());
      assertNull(s.tags());
    }
  }

  @Nested
  @DisplayName("BigDecimal / BigInteger — DEFAULT substitutes canonical ZERO")
  class BigNumerics {

    record MoneySrc(java.math.BigDecimal balance, java.math.BigInteger txCount) {}

    record MoneyDst(java.math.BigDecimal balance, java.math.BigInteger txCount) {}

    @Test
    @DisplayName("null BigDecimal → BigDecimal.ZERO; null BigInteger → BigInteger.ZERO")
    void bigNumericsGetZero() {
      final var mapper = Telescope.mapper(MoneySrc.class, MoneyDst.class, nullSourceValues(DEFAULT));

      final var defaulted = mapper.forward(new MoneySrc(null, null));

      assertEquals(java.math.BigDecimal.ZERO, defaulted.balance(), "BigDecimal → ZERO");
      assertEquals(java.math.BigInteger.ZERO, defaulted.txCount(), "BigInteger → ZERO");
    }
  }

  @Nested
  @DisplayName("Records and beans uniformly — DEFAULT applies to bean targets too")
  class BeanTargets {

    static class BeanDst {

      private String name;
      private Integer count;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer count) {
        this.count = count;
      }

      public BeanDst() {}
    }

    record SimpleSrc(String name, Integer count) {}

    @Test
    @DisplayName("DEFAULT applies to bean-target fields the same way it applies to record-target fields")
    void beanTargetGetsDefaults() {
      final var mapper = Telescope.mapper(SimpleSrc.class, BeanDst.class, nullSourceValues(DEFAULT));

      final var dst = mapper.forward(new SimpleSrc(null, null));

      assertEquals("", dst.getName(), "bean String setter → \"\"");
      assertEquals(0, dst.getCount(), "bean Integer setter → 0");
    }
  }
}
