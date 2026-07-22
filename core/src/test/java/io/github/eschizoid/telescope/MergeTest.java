package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end probe of {@link Telescope#merge(Class,
 * io.github.eschizoid.telescope.mapping.MergeStep[])} — the N-source forward-only mapper. Single
 * arity-agnostic surface: every row carries its source class via {@code SerializedLambda}
 * inference; the {@link Sources} bag at forward time is class-keyed.
 *
 * <p>Covers the {@code from(...)} row factory, the {@code auto(...)} same-name backfill, the
 * unsupported-backward contract, build-time guards (null accessor, duplicate target), the
 * forward-time missing-source guard, the {@code Sources.of(...)} varargs shortcut, the {@link
 * Sources.Builder} fluent path, the same-class rejection, and scaling past arity 3 (this file
 * exercises arity 2/3/5 without any per-arity ceremony).
 */
class MergeTest {

  record Customer(String id, String email) {}

  record Audit(String createdBy, String createdAt) {}

  record LineItem(long totalCents) {}

  record Tax(double rate) {}

  record Promo(String code) {}

  record Profile(String id, String email, String createdBy, String createdAt) {}

  record Invoice(String id, String createdBy, long totalCents, double taxRate, String promoCode) {}

  @Nested
  @DisplayName("from(srcAcc, tgtAcc) — class-inferred slot, arity 2")
  class Arity2 {

    @Test
    @DisplayName("forward assembles a target from two sources, class inference picks each slot")
    void forwardAssembles() {
      final var c = new Customer("c-1", "a@b.com");
      final var a = new Audit("system", "2026-01-01");

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-1", "a@b.com", "system", "2026-01-01"), mapper.forward(Sources.of(c, a)));
    }

    @Test
    @DisplayName("row order is irrelevant — name-keyed dispatch")
    void rowOrderIrrelevant() {
      final var c = new Customer("c-2", "x@y.com");
      final var a = new Audit("admin", "2026-02-02");

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Audit::createdAt, Profile::createdAt),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Customer::id, Profile::id)
      );

      assertEquals(new Profile("c-2", "x@y.com", "admin", "2026-02-02"), mapper.forward(Sources.of(c, a)));
    }
  }

  // A 3-source target with only Object fields, so the Arity3 test can exercise partial coverage
  // without hitting the canonical-ctor's primitive-null limitation (which the Arity5 test pins
  // explicitly).
  record Receipt(String id, String createdBy, String label) {}

  @Nested
  @DisplayName("arity 3 — same factory, one more source")
  class Arity3 {

    @Test
    @DisplayName("3-source forward — same factory shape, just more rows")
    void threeSourceForward() {
      final var c = new Customer("c-3", "k@l.com");
      final var a = new Audit("u3", "2026-03-03");
      final var p = new Promo("LAUNCH");

      final Mapper<Sources, Receipt> mapper = Telescope.merge(
        Receipt.class,
        from(Customer::id, Receipt::id),
        from(Audit::createdBy, Receipt::createdBy),
        from(Promo::code, Receipt::label)
      );

      assertEquals(new Receipt("c-3", "u3", "LAUNCH"), mapper.forward(Sources.of(c, a, p)));
    }
  }

  @Nested
  @DisplayName("arity 5 — single factory scales without per-arity overloads")
  class Arity5 {

    @Test
    @DisplayName("5-source forward — same factory, 5 sources, 5 rows")
    void fiveSourceForward() {
      final var c = new Customer("c-5", "z@z.com");
      final var a = new Audit("u5", "2026-05-05");
      final var li = new LineItem(2500L);
      final var tax = new Tax(0.07);
      final var promo = new Promo("SUMMER25");

      final Mapper<Sources, Invoice> mapper = Telescope.merge(
        Invoice.class,
        from(Customer::id, Invoice::id),
        from(Audit::createdBy, Invoice::createdBy),
        from(LineItem::totalCents, Invoice::totalCents),
        from(Tax::rate, Invoice::taxRate),
        from(Promo::code, Invoice::promoCode)
      );

      assertEquals(new Invoice("c-5", "u5", 2500L, 0.07, "SUMMER25"), mapper.forward(Sources.of(c, a, li, tax, promo)));
    }
  }

  @Nested
  @DisplayName("Sources.builder() — fluent / conditional source assembly")
  class FluentBuilder {

    @Test
    @DisplayName("builder().with(...).build() is interchangeable with Sources.of(...)")
    void builderMatchesVarargs() {
      final var c = new Customer("c-9", "z@z.com");
      final var a = new Audit("u9", "2026-09-09");

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id),
        from(Customer::email, Profile::email),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(mapper.forward(Sources.of(c, a)), mapper.forward(Sources.builder().with(c).with(a).build()));
    }
  }

  @Nested
  @DisplayName("auto(Class<S>) — same-name same-type backfill")
  class AutoBackfill {

    @Test
    @DisplayName("auto(Customer.class) backfills id + email; explicit rows handle the rest")
    void autoBackfillsMatchingNames() {
      final var c = new Customer("c-7", "n@m.com");
      final var a = new Audit("auditor7", "2026-07-07");

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        auto(Customer.class),
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-7", "n@m.com", "auditor7", "2026-07-07"), mapper.forward(Sources.of(c, a)));
    }

    @Test
    @DisplayName("explicit rows take precedence over auto() — auto silently skips already-claimed names")
    void autoSkipsAlreadyClaimed() {
      final var c = new Customer("c-8", "o@p.com");
      final var a = new Audit("auditor8", "2026-08-08");

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id), // claim id explicitly
        auto(Customer.class), // backfills email only
        from(Audit::createdBy, Profile::createdBy),
        from(Audit::createdAt, Profile::createdAt)
      );

      assertEquals(new Profile("c-8", "o@p.com", "auditor8", "2026-08-08"), mapper.forward(Sources.of(c, a)));
    }

    // R2 — auto-backfill is loud, not silent, on type mismatch.
    record TypoCustomer(long id, String email) {}

    @Test
    @DisplayName("auto() throws at build time when a name matches but types differ — masked null was a footgun")
    void autoTypeMismatchLoud() {
      // TypoCustomer.id is long; Profile.id is String. Name matches by accident; types diverge.
      // Pre-R2 this silently dropped the row and the user got a null id at runtime.
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Profile.class,
          auto(TypoCustomer.class),
          from(Audit::createdBy, Profile::createdBy),
          from(Audit::createdAt, Profile::createdAt)
        )
      );
      assertTrue(ex.getMessage().contains("'id'"));
      assertTrue(ex.getMessage().contains("types differ"));
    }
  }

  @Nested
  @DisplayName("backward direction — unsupported")
  class BackwardUnsupported {

    @Test
    @DisplayName("backward throws UnsupportedOperationException with a self-diagnosing message")
    void backwardThrows() {
      final Mapper<Sources, Profile> mapper = Telescope.merge(Profile.class, from(Customer::id, Profile::id));
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
    @DisplayName("sourceClass is Sources; targetClass is the target record")
    void exposesClasses() {
      final Mapper<Sources, Profile> mapper = Telescope.merge(Profile.class, from(Customer::id, Profile::id));
      assertEquals(Sources.class, mapper.sourceClass());
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

      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id),
        from(Audit::createdBy, Profile::createdBy)
      );

      assertEquals(new Profile("c-3", null, "auditor", null), mapper.forward(Sources.of(c, a)));
    }
  }

  @Nested
  @DisplayName("build-time guards")
  class BuildTimeGuards {

    @Test
    @DisplayName("duplicate target field across rows throws at build time")
    void duplicateTargetThrows() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Profile.class,
          from(Customer::id, Profile::id),
          from(Audit::createdBy, Profile::id) // second row also writes 'id'
        )
      );
      assertTrue(ex.getMessage().contains("'id'"));
      assertTrue(ex.getMessage().toLowerCase().contains("at most one"));
    }

    @Test
    @DisplayName("null source accessor in from(...) throws with a row index")
    void nullSourceAccessor() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.merge(
          Profile.class,
          from((io.github.eschizoid.telescope.Telescope.Accessor<Customer, String>) null, Profile::id)
        )
      );
      assertTrue(ex.getMessage().contains("index 0"));
      assertTrue(ex.getMessage().toLowerCase().contains("source"));
    }
  }

  @Nested
  @DisplayName("forward-time guards")
  class ForwardTimeGuards {

    @Test
    @DisplayName("forward called with a Sources bag missing one of the row source classes throws naming the class")
    void missingSourceClassThrows() {
      final Mapper<Sources, Profile> mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id),
        from(Audit::createdBy, Profile::createdBy)
      );

      // Bag has only Customer — Audit row will fail at forward time with a precise diagnostic
      // that names BOTH the missing class and what's actually present in the bag (R4).
      final var bag = Sources.of(new Customer("c-1", "x@y.com"));
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.forward(bag));
      assertTrue(ex.getMessage().contains("Audit"));
      assertTrue(ex.getMessage().contains("createdBy"));
      assertTrue(
        ex.getMessage().contains("Customer"),
        () -> "expected bag contents in message, got: " + ex.getMessage()
      );
    }
  }

  @Nested
  @DisplayName("Sources distinct-class constraint")
  class DistinctClasses {

    @Test
    @DisplayName("Sources.of(...) rejects two values sharing a runtime class")
    void duplicateClassRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Sources.of(new Customer("c-1", "x"), new Customer("c-2", "y"))
      );
      assertTrue(ex.getMessage().contains("Customer"));
      assertTrue(ex.getMessage().toLowerCase().contains("distinct class"));
    }

    @Test
    @DisplayName("Sources.builder().with(...) likewise rejects duplicate classes")
    void duplicateClassBuilderRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Sources.builder().with(new Customer("c-1", "x")).with(new Customer("c-2", "y"))
      );
      assertTrue(ex.getMessage().contains("Customer"));
    }
  }

  // A mutable POJO target: no-arg ctor + setters => the setters write strategy. The common
  // bean-target shape, and the one the merge engine's bean path assembles.
  static final class SettersProfile {

    private String id;
    private String region;

    public SettersProfile() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(final String region) {
      this.region = region;
    }
  }

  // A field-injection target: no-arg ctor, NO setters, no static builder => the field write
  // strategy. `label` is a getter-only computed property with no backing field, so the field
  // writer skips it entirely — the corner where a lazily-read bound row could swallow a
  // source-missing throw.
  static final class FieldsTarget {

    private String id;

    public FieldsTarget() {}

    public String getId() {
      return id;
    }

    public String getLabel() {
      return "label-of-" + id;
    }
  }

  @Nested
  @DisplayName("bean targets — the merge engine's bean write path")
  class BeanTargets {

    @Test
    @DisplayName("forward assembles a mutable POJO target through its setters")
    void beanTargetForwardAssembles() {
      final Mapper<Sources, SettersProfile> mapper = Telescope.merge(
        SettersProfile.class,
        from(Customer::id, SettersProfile::getId),
        from(Audit::createdBy, SettersProfile::getRegion)
      );

      final var result = mapper.forward(Sources.of(new Customer("c-9", "z@z.com"), new Audit("auditor", "2026-07-21")));
      assertEquals("c-9", result.getId());
      assertEquals("auditor", result.getRegion());
    }

    @Test
    @DisplayName("a bean target left partially unmapped leaves the untouched property null")
    void beanTargetUnmappedPropertyNull() {
      final Mapper<Sources, SettersProfile> mapper = Telescope.merge(
        SettersProfile.class,
        from(Customer::id, SettersProfile::getId)
      );

      final var result = mapper.forward(Sources.of(new Customer("c-9", "z@z.com")));
      assertEquals("c-9", result.getId());
      assertEquals(null, result.getRegion());
    }

    @Test
    @DisplayName("a missing source still throws even when the field writer would skip that bound property")
    void beanFieldWriterMissingSourceStillThrows() {
      // The row binds Audit->getLabel, a getter-only property the field writer skips at construct
      // time. A lazily-read bound row would never be queried, swallowing the source-missing throw;
      // the eager read keeps the guarantee that an absent source is reported.
      final Mapper<Sources, FieldsTarget> mapper = Telescope.merge(
        FieldsTarget.class,
        from(Customer::id, FieldsTarget::getId),
        from(Audit::createdBy, FieldsTarget::getLabel)
      );

      final var bag = Sources.of(new Customer("c-1", "x@y.com")); // no Audit
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.forward(bag));
      assertTrue(ex.getMessage().contains("Audit"));
      assertTrue(ex.getMessage().contains("label"));
    }
  }
}
