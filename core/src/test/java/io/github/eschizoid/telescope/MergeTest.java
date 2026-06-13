package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep2.first;
import static io.github.eschizoid.telescope.mapping.MergeStep2.from;
import static io.github.eschizoid.telescope.mapping.MergeStep2.second;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end probe of {@link Telescope#merge(Class, Class, Class,
 * io.github.eschizoid.telescope.mapping.MergeStep[])} — the two-source forward-only mapper.
 *
 * <p>Covers all three row factories ({@code from} with class inference, explicit {@code first} /
 * {@code second}), the unsupported-backward contract, the build-time guards (null accessors,
 * duplicate target, slot inference mismatch), {@code auto(...)} same-name backfill, arity-3
 * {@code Sources3} mapping, and the {@code Sources.of(...)} tuple shortcut.
 */
class MergeTest {

  record Customer(String id, String email) {}

  record Audit(String createdBy, String createdAt) {}

  record Profile(String id, String email, String createdBy, String createdAt) {}

  @Nested
  @DisplayName("from(srcAcc, tgtAcc) — class inference (recommended)")
  class FromInference {

    @Test
    @DisplayName("forward assembles target from inferred slots")
    void forwardAssembles() {
      final var c = new Customer("c-1", "a@b.com");
      final var a = new Audit("system", "2026-01-01");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-1", "a@b.com", "system", "2026-01-01"), mapper.forward(new Sources2<>(c, a)));
    }

    @Test
    @DisplayName("row order is irrelevant — name-keyed dispatch")
    void rowOrderIrrelevant() {
      final var c = new Customer("c-2", "x@y.com");
      final var a = new Audit("admin", "2026-02-02");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Audit::createdAt, Profile::createdAt),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Customer::id, Profile::id)
      );

      assertEquals(new Profile("c-2", "x@y.com", "admin", "2026-02-02"), mapper.forward(new Sources2<>(c, a)));
    }
  }

  @Nested
  @DisplayName("explicit first/second — escape hatch for same-typed sources")
  class ExplicitSlots {

    @Test
    @DisplayName("first(...) / second(...) still work for shared-class merge")
    void explicitSlotsWork() {
      final var c = new Customer("c-1", "e@x.com");
      final var a = new Audit("u", "2026-01-01");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        first(Customer::id, Profile::id),
        first(Customer::email, Profile::email),
        second(Audit::createdBy, Profile::createdBy),
        second(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-1", "e@x.com", "u", "2026-01-01"), mapper.forward(new Sources2<>(c, a)));
    }

    @Test
    @DisplayName("from / first / second can mix in one call")
    void mixedFactoriesCompose() {
      final var c = new Customer("c-1", "e@x.com");
      final var a = new Audit("u", "2026-01-01");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id),
        first(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        second(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-1", "e@x.com", "u", "2026-01-01"), mapper.forward(new Sources2<>(c, a)));
    }
  }

  @Nested
  @DisplayName("backward direction — unsupported")
  class BackwardUnsupported {

    @Test
    @DisplayName("backward throws UnsupportedOperationException with a self-diagnosing message")
    void backwardThrows() {
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id)
      );

      final var ex = assertThrows(UnsupportedOperationException.class, () ->
        mapper.backward(new Profile("x", "y", "z", "w"))
      );
      assertTrue(ex.getMessage().toLowerCase().contains("merge"));
    }
  }

  @Nested
  @DisplayName("type metadata")
  class TypeMetadata {

    @Test
    @DisplayName("sourceClass is Sources2; targetClass is the target record")
    void exposesClasses() {
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id)
      );

      assertEquals(Sources2.class, mapper.sourceClass());
      assertEquals(Profile.class, mapper.targetClass());
    }
  }

  @Nested
  @DisplayName("partial source coverage")
  class PartialCoverage {

    @Test
    @DisplayName("target components without a corresponding row default to null")
    void unmappedComponentNull() {
      final var c = new Customer("c-3", "p@q.com");
      final var a = new Audit("auditor", "2026-03-03");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id),
        from(Audit::createdBy, Profile::createdBy)
      );

      assertEquals(new Profile("c-3", null, "auditor", null), mapper.forward(new Sources2<>(c, a)));
    }
  }

  @Nested
  @DisplayName("build-time guards")
  class BuildTimeGuards {

    record Stray(String z) {}

    @Test
    @DisplayName("from(...) with a source class matching neither slot throws at build time")
    void fromMismatchedSourceClass() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Customer.class,
          Audit.class,
          Profile.class,
          from(Stray::z, Profile::id) // Stray is neither Customer nor Audit
        )
      );
      assertTrue(ex.getMessage().contains("Stray"), () -> "expected message to name Stray, was: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("Customer"));
      assertTrue(ex.getMessage().contains("Audit"));
    }

    @Test
    @DisplayName("duplicate target field across rows throws at build time")
    void duplicateTargetThrows() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Customer.class,
          Audit.class,
          Profile.class,
          from(Customer::id, Profile::id),
          from(Audit::createdBy, Profile::id) // second row also writes 'id'
        )
      );
      assertTrue(ex.getMessage().contains("'id'"));
      assertTrue(ex.getMessage().toLowerCase().contains("at most one"));
    }

    @Test
    @DisplayName("null source accessor in first(...) throws with a row index")
    void nullSourceAccessor() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Customer.class,
          Audit.class,
          Profile.class,
          first((io.github.eschizoid.telescope.Telescope.Accessor<Customer, String>) null, Profile::id)
        )
      );
      assertTrue(ex.getMessage().contains("index 0"));
      assertTrue(ex.getMessage().toLowerCase().contains("source"));
    }
  }

  @Nested
  @DisplayName("Sources.of(...) shortcut hides the new-Sources2/3 ceremony at the call site")
  class SourcesShortcut {

    @Test
    @DisplayName("Sources.of(a, b) is interchangeable with new Sources2<>(a, b)")
    void shortcutMatchesExplicitCtor() {
      final var c = new Customer("c-9", "z@z.com");
      final var a = new Audit("u9", "2026-09-09");
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );
      assertEquals(mapper.forward(new Sources2<>(c, a)), mapper.forward(Sources.of(c, a)));
    }
  }

  @Nested
  @DisplayName("auto(Class<S>) — same-name same-type backfill")
  class AutoBackfill {

    @Test
    @DisplayName("auto(Customer.class) backfills id + email; explicit rows handle the remaining target slots")
    void autoBackfillsMatchingNames() {
      final var c = new Customer("c-7", "n@m.com");
      final var a = new Audit("auditor7", "2026-07-07");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        io.github.eschizoid.telescope.mapping.MergeStep2.auto(Customer.class),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-7", "n@m.com", "auditor7", "2026-07-07"), mapper.forward(Sources.of(c, a)));
    }

    @Test
    @DisplayName("auto(Wrong.class) where Wrong is neither sourceA nor sourceB throws at build time")
    void autoMismatchedSourceClass() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Customer.class,
          Audit.class,
          Profile.class,
          io.github.eschizoid.telescope.mapping.MergeStep2.auto(BuildTimeGuards.Stray.class)
        )
      );
      assertTrue(ex.getMessage().contains("Stray"));
      assertTrue(ex.getMessage().toLowerCase().contains("auto"));
    }

    @Test
    @DisplayName("explicit rows take precedence over auto() — auto silently skips already-claimed names")
    void autoSkipsAlreadyClaimed() {
      final var c = new Customer("c-8", "o@p.com");
      final var a = new Audit("auditor8", "2026-08-08");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        from(Customer::id, Profile::id), // claim id explicitly
        io.github.eschizoid.telescope.mapping.MergeStep2.auto(Customer.class), // backfills email only
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-8", "o@p.com", "auditor8", "2026-08-08"), mapper.forward(Sources.of(c, a)));
    }
  }

  @Nested
  @DisplayName("arity 3 — Sources3 + MergeStep3 mirror the 2-source surface")
  class Arity3 {

    record LineItem(long totalCents) {}

    record Invoice(String id, String createdBy, long totalCents) {}

    @Test
    @DisplayName("3-source forward — from(...) infers each row's slot from the source class")
    void threeSourceForward() {
      final var c = new Customer("c-3", "k@l.com");
      final var a = new Audit("u3", "2026-03-03");
      final var li = new LineItem(1599L);

      final Mapper<Sources3<Customer, Audit, LineItem>, Invoice> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        LineItem.class,
        Invoice.class,
        io.github.eschizoid.telescope.mapping.MergeStep3.from(Customer::id, Invoice::id),
        io.github.eschizoid.telescope.mapping.MergeStep3.from(Audit::createdBy, Invoice::createdBy),
        io.github.eschizoid.telescope.mapping.MergeStep3.from(LineItem::totalCents, Invoice::totalCents)
      );

      assertEquals(new Invoice("c-3", "u3", 1599L), mapper.forward(Sources.of(c, a, li)));
    }

    @Test
    @DisplayName("3-source forward — explicit first/second/third still work for shared-class sources")
    void threeSourceExplicitSlots() {
      final var c = new Customer("c-4", "m@n.com");
      final var a = new Audit("u4", "2026-04-04");
      final var li = new LineItem(2500L);

      final Mapper<Sources3<Customer, Audit, LineItem>, Invoice> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        LineItem.class,
        Invoice.class,
        io.github.eschizoid.telescope.mapping.MergeStep3.first(Customer::id, Invoice::id),
        io.github.eschizoid.telescope.mapping.MergeStep3.second(Audit::createdBy, Invoice::createdBy),
        io.github.eschizoid.telescope.mapping.MergeStep3.third(LineItem::totalCents, Invoice::totalCents)
      );

      assertEquals(new Invoice("c-4", "u4", 2500L), mapper.forward(Sources.of(c, a, li)));
    }

    @Test
    @DisplayName("auto(LineItem.class) backfills totalCents on the arity-3 merge")
    void threeSourceAutoBackfill() {
      final var c = new Customer("c-5", "q@r.com");
      final var a = new Audit("u5", "2026-05-05");
      final var li = new LineItem(3700L);

      final Mapper<Sources3<Customer, Audit, LineItem>, Invoice> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        LineItem.class,
        Invoice.class,
        io.github.eschizoid.telescope.mapping.MergeStep3.from(Customer::id, Invoice::id),
        io.github.eschizoid.telescope.mapping.MergeStep3.from(Audit::createdBy, Invoice::createdBy),
        io.github.eschizoid.telescope.mapping.MergeStep3.auto(LineItem.class)
      );

      assertEquals(new Invoice("c-5", "u5", 3700L), mapper.forward(Sources.of(c, a, li)));
    }

    @Test
    @DisplayName("3-source backward is unsupported")
    void threeSourceBackwardThrows() {
      final Mapper<Sources3<Customer, Audit, LineItem>, Invoice> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        LineItem.class,
        Invoice.class,
        io.github.eschizoid.telescope.mapping.MergeStep3.from(Customer::id, Invoice::id)
      );
      assertThrows(UnsupportedOperationException.class, () -> mapper.backward(new Invoice("x", "y", 0L)));
    }
  }
}
