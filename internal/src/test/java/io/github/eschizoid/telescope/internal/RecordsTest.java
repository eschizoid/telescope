package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.Lens;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct contract tests for {@link Records} — the canonical-constructor read/write substrate that
 * the public {@code Telescope} record paths route through. Pins the null-source guards, the
 * unknown-field / non-record diagnostics, and the per-class {@code RecordInfo} cache behaviour.
 */
class RecordsTest {

  record User(String name, int age) {}

  record Account(String id, User owner, List<String> tags) {}

  static class NotARecord {}

  @Nested
  @DisplayName("Public read / write surface")
  class PublicSurface {

    @Test
    @DisplayName("read returns the focused component value")
    void readReturnsComponent() {
      final var user = new User("alice", 30);
      assertEquals("alice", Records.read(user, "name"));
      assertEquals(30, Records.read(user, "age"));
    }

    @Test
    @DisplayName("read(null, name) returns null — does not NPE on persistentClassOf-like lookup")
    void readOnNullSourceReturnsNull() {
      assertNull(Records.read(null, "name"));
    }

    @Test
    @DisplayName("read with an unknown component name throws IllegalArgumentException naming the missing field")
    void readUnknownComponentThrows() {
      final var user = new User("alice", 30);
      final var ex = assertThrows(IllegalArgumentException.class, () -> Records.read(user, "ghost"));
      assertTrue(
        ex.getMessage().contains("ghost"),
        () -> "diagnostic must name the missing field, got: " + ex.getMessage()
      );
    }

    @Test
    @DisplayName("with rebuilds the record with one component replaced; off-path components carry over")
    void withReplacesComponent() {
      final var src = new User("alice", 30);
      final User updated = Records.with(src, "age", 31);
      assertEquals(new User("alice", 31), updated);
    }

    @Test
    @DisplayName("with(null, ...) returns null — null source guard")
    void withOnNullSourceReturnsNull() {
      assertNull(Records.with((User) null, "name", "bob"));
    }

    @Test
    @DisplayName("with on a non-record source throws IllegalArgumentException")
    void withOnNonRecordThrows() {
      final var ex = assertThrows(IllegalArgumentException.class, () -> Records.with(new NotARecord(), "x", 1));
      assertTrue(ex.getMessage().contains("Not a record"), () -> ex.getMessage());
    }

    @Test
    @DisplayName("with on an unknown component throws IllegalArgumentException naming the missing field")
    void withUnknownComponentThrows() {
      final var src = new User("alice", 30);
      final var ex = assertThrows(IllegalArgumentException.class, () -> Records.with(src, "ghost", 1));
      assertTrue(ex.getMessage().contains("ghost"), () -> ex.getMessage());
    }

    @Test
    @DisplayName("construct assembles a record from a name-keyed value function")
    void constructAssemblesByName() {
      final var built = Records.construct(User.class, name ->
        switch (name) {
          case "name" -> "carol";
          case "age" -> 27;
          default -> throw new IllegalStateException("unexpected: " + name);
        }
      );
      assertEquals(new User("carol", 27), built);
    }

    @Test
    @DisplayName("componentNames returns the canonical-constructor order")
    void componentNamesAreInCanonicalOrder() {
      assertArrayEquals(new String[] { "name", "age" }, Records.componentNames(User.class));
      assertArrayEquals(new String[] { "id", "owner", "tags" }, Records.componentNames(Account.class));
    }

    @Test
    @DisplayName(
      "componentType returns the generic Type — preserving parameterised List<String> for downstream shape detection"
    )
    void componentTypePreservesGenerics() {
      // DeepMap shape detection (List<X>, Map<K, V>, Optional<X>) needs the generic Type, not the
      // erased Class — verify the wrapping shape survives the round-trip.
      final var type = Records.componentType(Account.class, "tags");
      assertTrue(type instanceof ParameterizedType, () -> "expected ParameterizedType for List<String>, got " + type);
      final var raw = ((ParameterizedType) type).getRawType();
      assertEquals(List.class, raw);
    }

    @Test
    @DisplayName("componentType throws on an unknown name")
    void componentTypeUnknownThrows() {
      assertThrows(IllegalArgumentException.class, () -> Records.componentType(User.class, "ghost"));
    }
  }

  @Nested
  @DisplayName("fieldLens(String) — class-deferred lens used by Telescope.fieldByName on records")
  class FieldLensDeferred {

    @Test
    @DisplayName("get reads the focused component via late-bound class dispatch")
    void getReadsComponent() {
      final Lens<User, String> nameLens = Records.fieldLens("name");
      assertEquals("alice", nameLens.get(new User("alice", 30)));
    }

    @Test
    @DisplayName("get(null) returns null instead of NPEing on info(null.getClass())")
    void getOnNullSourceIsNull() {
      final Lens<User, String> nameLens = Records.fieldLens("name");
      assertNull(nameLens.get(null));
    }

    @Test
    @DisplayName("set rebuilds with the focused component replaced")
    void setReplacesComponent() {
      final Lens<User, String> nameLens = Records.fieldLens("name");
      final var rebuilt = nameLens.set(new User("alice", 30), "bob");
      assertEquals(new User("bob", 30), rebuilt);
    }

    @Test
    @DisplayName("set(null, value) returns null — null source guard")
    void setOnNullSourceIsNull() {
      final Lens<User, String> nameLens = Records.fieldLens("name");
      assertNull(nameLens.set(null, "bob"));
    }

    @Test
    @DisplayName("modify applies the function to the current component and rebuilds")
    void modifyAppliesFunction() {
      final Lens<User, String> nameLens = Records.fieldLens("name");
      final var rebuilt = nameLens.modify(new User("Alice", 30), String::toLowerCase);
      assertEquals(new User("alice", 30), rebuilt);
    }
  }

  @Nested
  @DisplayName("fieldLens(Class, String) — class-aware lens used by method-reference .field(...) paths")
  class FieldLensClassAware {

    @Test
    @DisplayName("captures the per-class RecordInfo at construction; per-call dispatch reads without name lookup")
    void getReadsViaCapturedReader() {
      final Lens<User, Integer> ageLens = Records.fieldLens(User.class, "age");
      assertEquals(30, ageLens.get(new User("alice", 30)));
    }

    @Test
    @DisplayName("get(null) on the class-aware variant returns null")
    void getOnNullSourceIsNull() {
      final Lens<User, Integer> ageLens = Records.fieldLens(User.class, "age");
      assertNull(ageLens.get(null));
    }

    @Test
    @DisplayName("set rebuilds via the captured RecordInfo with one component replaced")
    void setReplacesComponent() {
      final Lens<User, Integer> ageLens = Records.fieldLens(User.class, "age");
      assertEquals(new User("alice", 31), ageLens.set(new User("alice", 30), 31));
    }

    @Test
    @DisplayName("constructing a lens for an unknown component fails fast at lens build time, not at first read")
    void unknownComponentFailsFastAtBuildTime() {
      // The class-aware variant resolves info+idx at construction — the IAE must fire at the
      // fieldLens(Class, String) call site, not later when .get/.set is invoked. Pins the
      // "fail fast" intent so a future refactor that defers the lookup won't slip through.
      assertThrows(IllegalArgumentException.class, () -> Records.fieldLens(User.class, "ghost"));
    }
  }

  @Nested
  @DisplayName("RecordInfo cache — info(...) and its non-record diagnostic")
  class CacheBehaviour {

    @Test
    @DisplayName("info(non-record) throws IllegalArgumentException with a precise diagnostic")
    void infoOnNonRecordThrows() {
      // info() is package-private; reach it via the public surface to keep the test through the
      // contract. componentNames is the simplest entry point that exercises info() directly.
      final var ex = assertThrows(IllegalArgumentException.class, () -> Records.componentNames(NotARecord.class));
      assertTrue(ex.getMessage().contains("Not a record"), () -> ex.getMessage());
    }

    @Test
    @DisplayName("info() returns the same RecordInfo instance across calls — per-class memoisation, no rebuild")
    void infoCacheReturnsSameInstance() {
      // The package-private info() method is the single cache entry point — calling it twice for
      // the same class must return the very same RecordInfo (LMF-built readers + ctorFn) instead
      // of rebuilding. A regression here would silently double the per-read overhead.
      assertSame(Records.info(User.class), Records.info(User.class));
      // And the cache must hold one entry per class — a second class doesn't collide.
      assertNotEquals(Records.info(User.class), Records.info(Account.class));
    }
  }
}
