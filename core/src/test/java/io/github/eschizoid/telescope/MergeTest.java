package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep.first;
import static io.github.eschizoid.telescope.mapping.MergeStep.second;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end probe of {@link Telescope#merge(Class, Class, Class,
 * io.github.eschizoid.telescope.mapping.MergeStep[])} — the two-source forward-only mapper that
 * eliminates the {@code Edit.over(...)} workaround for the ~60% of enterprise mappers whose forward
 * direction reads from more than one source object.
 *
 * <p>Backward is documented as unsupported — calling {@link Mapper#backward(Object)} (or {@link
 * Mapper#patch(Object, Object)}) on the returned mapper throws.
 */
class MergeTest {

  record Customer(String id, String email) {}

  record Audit(String createdBy, String createdAt) {}

  record Profile(String id, String email, String createdBy, String createdAt) {}

  @Nested
  @DisplayName("Telescope.merge(A, B, T, MergeStep[]) — happy path")
  class HappyPath {

    @Test
    @DisplayName("forward reads each row's slot and assembles a fresh target")
    void forwardAssembles() {
      final var c = new Customer("c-1", "a@b.com");
      final var a = new Audit("system", "2026-01-01");

      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        first(Customer::id, Profile::id),
        first(Customer::email, Profile::email),
        second(Audit::createdBy, Profile::createdBy),
        second(Audit::createdAt, Profile::createdAt)
      );

      final var result = mapper.forward(new Sources2<>(c, a));
      assertEquals(new Profile("c-1", "a@b.com", "system", "2026-01-01"), result);
    }

    @Test
    @DisplayName("row order is irrelevant — the target's component name determines placement")
    void rowOrderIrrelevant() {
      final var c = new Customer("c-2", "x@y.com");
      final var a = new Audit("admin", "2026-02-02");

      // Same rows in shuffled order — target component lookup is name-keyed, not positional.
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        second(Audit::createdAt, Profile::createdAt),
        first(Customer::email, Profile::email),
        second(Audit::createdBy, Profile::createdBy),
        first(Customer::id, Profile::id)
      );

      assertEquals(new Profile("c-2", "x@y.com", "admin", "2026-02-02"), mapper.forward(new Sources2<>(c, a)));
    }
  }

  @Nested
  @DisplayName("Backward direction — unsupported")
  class BackwardUnsupported {

    @Test
    @DisplayName("backward() throws UnsupportedOperationException — multi-source has no inverse")
    void backwardThrows() {
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        first(Customer::id, Profile::id),
        first(Customer::email, Profile::email),
        second(Audit::createdBy, Profile::createdBy),
        second(Audit::createdAt, Profile::createdAt)
      );

      final var p = new Profile("c-1", "a@b.com", "system", "2026-01-01");
      final var ex = assertThrows(UnsupportedOperationException.class, () -> mapper.backward(p));
      // Message should name the factory so the failure is self-diagnosing.
      org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().toLowerCase().contains("merge"),
        () -> "expected message to mention Telescope.merge, was: " + ex.getMessage()
      );
    }
  }

  @Nested
  @DisplayName("Type metadata")
  class TypeMetadata {

    @Test
    @DisplayName("sourceClass is Sources2; targetClass is the target record")
    void exposesClasses() {
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        first(Customer::id, Profile::id)
      );

      assertEquals(Sources2.class, mapper.sourceClass());
      assertEquals(Profile.class, mapper.targetClass());
    }
  }

  @Nested
  @DisplayName("Partial source coverage")
  class PartialCoverage {

    @Test
    @DisplayName("target components without a corresponding row are constructed with default (null)")
    void unmappedComponentNull() {
      final var c = new Customer("c-3", "p@q.com");
      final var a = new Audit("auditor", "2026-03-03");

      // Profile has 4 components; mapper only provides 2 (id, createdBy). The other two default to
      // null.
      final Mapper<Sources2<Customer, Audit>, Profile> mapper = Telescope.merge(
        Customer.class,
        Audit.class,
        Profile.class,
        first(Customer::id, Profile::id),
        second(Audit::createdBy, Profile::createdBy)
      );

      assertEquals(new Profile("c-3", null, "auditor", null), mapper.forward(new Sources2<>(c, a)));
    }
  }
}
