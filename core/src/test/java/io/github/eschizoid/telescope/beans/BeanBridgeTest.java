package io.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the runtime POJO&harr;record bridge {@code Telescope.fromBean(...).via*()}. */
class BeanBridgeTest {

  record UserRecord(String id, String email, String name) {}

  // POJO usable by viaFields (no-arg ctor + private fields) and viaConstructor (all-args ctor in
  // component order). Getters drive the forward direction.
  static final class UserPojo {

    private String id;
    private String email;
    private String name;

    UserPojo() {}

    UserPojo(final String id, final String email, final String name) {
      this.id = id;
      this.email = email;
      this.name = name;
    }

    public String getId() {
      return id;
    }

    public String getEmail() {
      return email;
    }

    public String getName() {
      return name;
    }
  }

  // POJO with a builder.
  static final class BuiltUser {

    private final String id;
    private final String email;
    private final String name;

    private BuiltUser(final String id, final String email, final String name) {
      this.id = id;
      this.email = email;
      this.name = name;
    }

    public String getId() {
      return id;
    }

    public String getEmail() {
      return email;
    }

    public String getName() {
      return name;
    }

    public static Builder builder() {
      return new Builder();
    }

    static final class Builder {

      private String id;
      private String email;
      private String name;

      public Builder id(final String id) {
        this.id = id;
        return this;
      }

      public Builder email(final String email) {
        this.email = email;
        return this;
      }

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public BuiltUser build() {
        return new BuiltUser(id, email, name);
      }
    }
  }

  @Nested
  @DisplayName("viaFields — no-arg ctor + field injection")
  class ViaFields {

    static final Telescope<UserPojo, UserRecord> BRIDGE = Telescope.fromBean(UserPojo.class)
      .to(UserRecord.class)
      .viaFields();

    @Test
    @DisplayName("forward reads the POJO's getters into a record")
    void forward() {
      assertEquals(new UserRecord("u1", "A@X", "Alice"), BRIDGE.read(new UserPojo("u1", "A@X", "Alice")));
    }

    @Test
    @DisplayName("round-trip set(pojo, read(pojo)) reconstructs an equal POJO")
    void roundTrip() {
      final var pojo = new UserPojo("u1", "A@X", "Alice");
      final var back = BRIDGE.set(pojo, BRIDGE.read(pojo));
      assertEquals("u1", back.getId());
      assertEquals("A@X", back.getEmail());
      assertEquals("Alice", back.getName());
    }

    @Test
    @DisplayName("update transforms a field and writes it back to a new POJO")
    void update() {
      final var pojo = new UserPojo("u1", "A@X", "Alice");
      final var lowered = BRIDGE.update(pojo, r -> new UserRecord(r.id(), r.email().toLowerCase(), r.name()));
      assertEquals("a@x", lowered.getEmail());
      assertEquals("Alice", lowered.getName());
    }
  }

  @Nested
  @DisplayName("viaConstructor — positional all-args constructor")
  class ViaConstructor {

    static final Telescope<UserPojo, UserRecord> BRIDGE = Telescope.fromBean(UserPojo.class)
      .to(UserRecord.class)
      .viaConstructor();

    @Test
    @DisplayName("round-trip via the all-args constructor")
    void roundTrip() {
      final var pojo = new UserPojo("u1", "A@X", "Alice");
      final var back = BRIDGE.set(pojo, BRIDGE.read(pojo));
      assertEquals("u1", back.getId());
      assertEquals("A@X", back.getEmail());
      assertEquals("Alice", back.getName());
    }
  }

  @Nested
  @DisplayName("viaBuilder — static builder()")
  class ViaBuilder {

    static final Telescope<BuiltUser, UserRecord> BRIDGE = Telescope.fromBean(BuiltUser.class)
      .to(UserRecord.class)
      .viaBuilder();

    @Test
    @DisplayName("round-trip through the builder")
    void roundTrip() {
      final var pojo = BuiltUser.builder().id("u1").email("A@X").name("Alice").build();
      final var back = BRIDGE.set(pojo, BRIDGE.read(pojo));
      assertEquals("u1", back.getId());
      assertEquals("A@X", back.getEmail());
      assertEquals("Alice", back.getName());
    }
  }

  @Nested
  @DisplayName("composition")
  class Composition {

    record Page(List<UserPojo> items) {}

    @Test
    @DisplayName("a bean bridge threads through a longer optic path")
    void threadsThroughPath() {
      final var page = new Page(List.of(new UserPojo("u1", "A@X", "Alice"), new UserPojo("u2", "B@Y", "Bob")));
      final var bridge = Telescope.fromBean(UserPojo.class).to(UserRecord.class).viaFields();

      final var lowered = Telescope.of(Page.class)
        .each(Page::items)
        .then(bridge)
        .field(UserRecord::email)
        .update(page, String::toLowerCase);

      assertEquals("a@x", lowered.items().get(0).getEmail());
      assertEquals("b@y", lowered.items().get(1).getEmail());
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    record Renamed(String id, String fullName) {}

    record TwoArg(String id, String email) {}

    @Test
    @DisplayName("a record component with no matching getter fails at the via call")
    void nameMismatch() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.fromBean(UserPojo.class).to(Renamed.class).viaFields()
      );
      assertTrue(ex.getMessage().contains("fullName"), ex.getMessage());
    }

    @Test
    @DisplayName("viaConstructor with no matching-arity constructor fails")
    void noMatchingConstructor() {
      // UserPojo has a 0-arg and a 3-arg ctor, but no 2-arg ctor for a 2-component record.
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.fromBean(UserPojo.class).to(TwoArg.class).viaConstructor()
      );
      assertTrue(ex.getMessage().contains("2 parameters"), ex.getMessage());
    }

    @Test
    @DisplayName("viaBuilder on a POJO with no builder() fails")
    void noBuilder() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.fromBean(UserPojo.class).to(UserRecord.class).viaBuilder()
      );
      assertTrue(ex.getMessage().contains("builder()"), ex.getMessage());
    }
  }
}
