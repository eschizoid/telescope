package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Reflective#structuralIso(Class)} — the new lattice-primitive that turns a {@code
 * Reflective} impl into an {@code Iso<Map<String, Object>, T>} mediating between a name-keyed map
 * and a concrete instance.
 *
 * <p>Used by {@code DeepMap.assembleIso} to express the per-pair {@code Iso<S, T>} as pure {@code
 * .then(...)} composition rather than an inline forward/backward lambda body.
 */
class ReflectiveStructuralIsoTest {

  record User(String name, int age) {}

  static final class UserPojo {

    private String name;
    private int age;

    public UserPojo() {}

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setAge(final int age) {
      this.age = age;
    }
  }

  @Nested
  @DisplayName("Records — structuralIso forward = canonical-ctor build, backward = component reads")
  class RecordSide {

    @Test
    @DisplayName("forward build from a name-keyed map returns a User; backward read decomposes")
    void roundTrip() {
      final var iso = Reflective.RECORDS.structuralIso(User.class);
      final Map<String, Object> built = Map.of("name", "alice", "age", 30);
      final User user = iso.to(built);
      assertEquals(new User("alice", 30), user);

      final Map<String, Object> decomposed = iso.from(user);
      assertEquals("alice", decomposed.get("name"));
      assertEquals(30, decomposed.get("age"));
    }

    @Test
    @DisplayName("backward output is a LinkedHashMap in component-declaration order")
    void componentOrderPreserved() {
      final var iso = Reflective.RECORDS.structuralIso(User.class);
      final Map<String, Object> decomposed = iso.from(new User("alice", 30));
      assertEquals(List.of("name", "age"), new ArrayList<>(decomposed.keySet()));
    }
  }

  @Nested
  @DisplayName("Beans — structuralIso forward = autoWriter build, backward = getter reads")
  class BeanSide {

    @Test
    @DisplayName("forward build from a name-keyed map returns a populated POJO")
    void roundTrip() {
      final var iso = Reflective.BEANS.structuralIso(UserPojo.class);
      final Map<String, Object> built = new LinkedHashMap<>();
      built.put("name", "bob");
      built.put("age", 25);
      final UserPojo pojo = iso.to(built);
      assertEquals("bob", pojo.getName());
      assertEquals(25, pojo.getAge());

      final Map<String, Object> decomposed = iso.from(pojo);
      assertEquals("bob", decomposed.get("name"));
      assertEquals(25, decomposed.get("age"));
    }
  }

  @Nested
  @DisplayName("structuralIsoArr — Object[] intermediate variant, the runtime mapper's hot path")
  class StructuralArr {

    @Test
    @DisplayName("Records — round-trip via Object[] preserves component order and values")
    void recordRoundTrip() {
      final var iso = Reflective.RECORDS.structuralIsoArr(User.class);
      final Object[] in = { "alice", 30 };
      final User built = iso.to(in);
      assertEquals(new User("alice", 30), built);

      final Object[] out = iso.from(built);
      assertEquals("alice", out[0]);
      assertEquals(30, out[1]);
      assertEquals(2, out.length);
    }

    @Test
    @DisplayName("Records — backward emits a fresh Object[arity] (independent of input array)")
    void recordBackwardAllocatesFresh() {
      final var iso = Reflective.RECORDS.structuralIsoArr(User.class);
      final Object[] a = iso.from(new User("carol", 40));
      final Object[] b = iso.from(new User("carol", 40));
      assertEquals(a[0], b[0]);
      assertEquals(a[1], b[1]);
      // Distinct backing arrays — write to one must not bleed into the other.
      a[0] = "tampered";
      assertEquals("carol", b[0]);
    }

    @Test
    @DisplayName("Beans — round-trip via Object[] populates and reads positional slots")
    void beanRoundTrip() {
      final var iso = Reflective.BEANS.structuralIsoArr(UserPojo.class);
      final Object[] in = { "bob", 25 };
      final UserPojo built = iso.to(in);
      assertEquals("bob", built.getName());
      assertEquals(25, built.getAge());

      final Object[] out = iso.from(built);
      assertEquals("bob", out[0]);
      assertEquals(25, out[1]);
    }

    @Test
    @DisplayName("Records — Iso.reverse() inversion law: to(from(x)) == x and from(to(arr)) equals arr by slot")
    void recordReverseInverts() {
      final var iso = Reflective.RECORDS.structuralIsoArr(User.class);
      final User u = new User("dave", 55);
      assertEquals(u, iso.to(iso.from(u)));

      final var reversed = iso.reverse();
      final User again = reversed.from(reversed.to(u));
      assertEquals(u, again);
    }

    @Test
    @DisplayName("Beans — Iso.reverse() inversion law on the bean branch")
    void beanReverseInverts() {
      final var iso = Reflective.BEANS.structuralIsoArr(UserPojo.class);
      final UserPojo p = new UserPojo();
      p.setName("eve");
      p.setAge(33);
      final UserPojo round = iso.to(iso.from(p));
      assertEquals(p.getName(), round.getName());
      assertEquals(p.getAge(), round.getAge());
    }
  }
}
