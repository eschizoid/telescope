package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElseGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Mapping.toOrElse(src, tgt, default)} and {@code Mapping.toOrElseGet(src, tgt,
 * supplier)} — the null-coalescing defaults that close the gap with MapStruct's {@code
 * defaultValue} / {@code defaultExpression}. When the source accessor returns {@code null}, the
 * target receives the configured default (or the supplier's value) instead of the lambda dance
 * users would otherwise write inline.
 */
class MappingOrElseTest {

  record Src(String region, Integer count) {}

  record Dst(String region, Integer count) {}

  @Nested
  @DisplayName("Mapping.toOrElse — constant default")
  class ConstantDefault {

    @Test
    @DisplayName("null source → default lands on target")
    void nullSourceUsesDefault() {
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        toOrElse(Src::region, Dst::region, "DEFAULT-REGION"),
        toOrElse(Src::count, Dst::count, 0)
      );

      assertEquals(new Dst("DEFAULT-REGION", 0), mapper.forward(new Src(null, null)));
    }

    @Test
    @DisplayName("non-null source → source value passes through unchanged")
    void nonNullSourcePassesThrough() {
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        toOrElse(Src::region, Dst::region, "DEFAULT-REGION"),
        toOrElse(Src::count, Dst::count, 0)
      );

      assertEquals(new Dst("US-WEST", 42), mapper.forward(new Src("US-WEST", 42)));
    }
  }

  @Nested
  @DisplayName("Mapping.toOrElseGet — supplier default (lazy)")
  class SupplierDefault {

    @Test
    @DisplayName("null source → supplier is invoked; non-null source → supplier is NOT invoked")
    void supplierLazyEvaluation() {
      final var calls = new AtomicInteger();
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        toOrElseGet(Src::region, Dst::region, () -> {
          calls.incrementAndGet();
          return "LAZY-DEFAULT";
        })
      );

      // null source → supplier runs once.
      assertEquals("LAZY-DEFAULT", mapper.forward(new Src(null, 1)).region());
      assertEquals(1, calls.get(), "supplier invoked on null source");

      // non-null source → supplier NOT invoked, source value lands.
      assertEquals("EU-WEST", mapper.forward(new Src("EU-WEST", 1)).region());
      assertEquals(1, calls.get(), "supplier NOT invoked when source is non-null");
    }
  }

  @Nested
  @DisplayName("Mapping.toOrElse — predicate-gated (4-arg overload)")
  class PredicateGatedDefault {

    record Wide(String region, List<Integer> items) {}

    @Test
    @DisplayName("predicate fires the default on empty string, not just null")
    void blankStringFires() {
      final var mapper = Telescope.mapper(
        Wide.class,
        Wide.class,
        toOrElse(Wide::region, Wide::region, "DEFAULT", String::isBlank)
      );

      assertEquals("DEFAULT", mapper.forward(new Wide("", List.of(1))).region(), "empty string → default");
      assertEquals("DEFAULT", mapper.forward(new Wide("   ", List.of(1))).region(), "whitespace → default");
      assertEquals("US", mapper.forward(new Wide("US", List.of(1))).region(), "non-blank → passes through");
    }

    @Test
    @DisplayName("predicate fires the default on empty collection")
    void emptyCollectionFires() {
      final var mapper = Telescope.mapper(
        Wide.class,
        Wide.class,
        toOrElse(Wide::items, Wide::items, List.<Integer>of(99), List::isEmpty)
      );

      assertEquals(List.of(99), mapper.forward(new Wide("US", List.of())).items());
      assertEquals(List.of(1, 2), mapper.forward(new Wide("US", List.of(1, 2))).items());
    }
  }

  @Nested
  @DisplayName("Mapping.toOrElseGet — predicate-gated (4-arg overload)")
  class PredicateGatedSupplierDefault {

    @Test
    @DisplayName("supplier fires only when predicate matches")
    void supplierLazyOnPredicate() {
      final var calls = new AtomicInteger();
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        toOrElseGet(
          Src::region,
          Dst::region,
          () -> {
            calls.incrementAndGet();
            return "GENERATED";
          },
          String::isBlank
        )
      );

      // empty → supplier fires
      assertEquals("GENERATED", mapper.forward(new Src("", 1)).region());
      assertEquals(1, calls.get());

      // non-blank, non-null → predicate is false, supplier is NOT invoked
      assertEquals("US", mapper.forward(new Src("US", 1)).region());
      assertEquals(1, calls.get(), "supplier NOT invoked when predicate is false");

      // null → null-short-circuit BEFORE the predicate, so `String::isBlank` doesn't NPE on null.
      // Supplier fires; result is the supplier's value.
      assertEquals("GENERATED", mapper.forward(new Src(null, 1)).region());
      assertEquals(2, calls.get(), "supplier invoked on null source via null-short-circuit");
    }

    @Test
    @DisplayName("predicate-gated toOrElse(value, predicate) null-short-circuits before the predicate")
    void valueDefaultNullSafe() {
      final var mapper = Telescope.mapper(
        Src.class,
        Dst.class,
        toOrElse(Src::region, Dst::region, "FALLBACK", String::isBlank)
      );

      // null → null-short-circuit, predicate not called, default lands on target
      assertEquals("FALLBACK", mapper.forward(new Src(null, 1)).region());
      // blank → predicate true, default lands
      assertEquals("FALLBACK", mapper.forward(new Src("   ", 1)).region());
      // non-blank → predicate false, source passes through
      assertEquals("UK", mapper.forward(new Src("UK", 1)).region());
    }
  }
}
