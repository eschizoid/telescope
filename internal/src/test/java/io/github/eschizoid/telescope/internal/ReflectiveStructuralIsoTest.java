package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.MetadataHolderProbe;
import io.github.eschizoid.telescope.internal.Reflective;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
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
      assertEquals(java.util.List.of("name", "age"), new java.util.ArrayList<>(decomposed.keySet()));
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
}
