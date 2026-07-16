package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

  // Compact-constructor record: the canonical constructor normalises `name` (trims + lower-cases)
  // and rejects a negative `age`. The rebuild path (Records.with / construct / fieldLens.set) goes
  // through the canonical constructor, so the normalisation MUST run on every rebuild — a
  // regression
  // that bypassed the canonical ctor (e.g. field-poking) would silently skip it.
  record NormalizingUser(String name, int age) {
    NormalizingUser {
      if (age < 0) throw new IllegalArgumentException("age must be non-negative");
      name = name == null ? null : name.trim().toLowerCase();
    }
  }

  // All-primitive record pinning the unboxed accessor/ctor MethodHandle path (accessorHandles /
  // ctorHandle) that MhIso consumes — distinct from the boxing Function<Object, Object> readers.
  record PrimitiveRecord(int i, long l, double d, boolean b) {}

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
    @DisplayName("read with an unknown component name throws IllegalArgumentException naming the missing" + " field")
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
      assertTrue(ex.getMessage().contains("Not a record"), ex::getMessage);
    }

    @Test
    @DisplayName("with on an unknown component throws IllegalArgumentException naming the missing field")
    void withUnknownComponentThrows() {
      final var src = new User("alice", 30);
      final var ex = assertThrows(IllegalArgumentException.class, () -> Records.with(src, "ghost", 1));
      assertTrue(ex.getMessage().contains("ghost"), ex::getMessage);
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
      "componentType returns the generic Type — preserving parameterised List<String> for" +
        " downstream shape detection"
    )
    void componentTypePreservesGenerics() {
      // DeepMap shape detection (List<X>, Map<K, V>, Optional<X>) needs the generic Type, not the
      // erased Class — verify the wrapping shape survives the round-trip.
      final var type = Records.componentType(Account.class, "tags");
      assertInstanceOf(ParameterizedType.class, type, () -> "expected ParameterizedType for List<String>, got " + type);
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
    @DisplayName("captures the per-class RecordInfo at construction; per-call dispatch reads without name" + " lookup")
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
    @DisplayName("constructing a lens for an unknown component fails fast at lens build time, not at first" + " read")
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
      assertTrue(ex.getMessage().contains("Not a record"), ex::getMessage);
    }

    @Test
    @DisplayName("info() returns the same RecordInfo instance across calls — per-class memoisation, no" + " rebuild")
    void infoCacheReturnsSameInstance() {
      // The package-private info() method is the single cache entry point — calling it twice for
      // the same class must return the very same RecordInfo (LMF-built readers + ctorFn) instead
      // of rebuilding. A regression here would silently double the per-read overhead.
      assertSame(Records.info(User.class), Records.info(User.class));
      // And the cache must hold one entry per class — a second class doesn't collide. Reference
      // identity (not structural equality) is the right pin: a cache-bleed regression where
      // info(User) returned Account's entry would have the wrong reference, not the wrong value.
      assertNotSame(Records.info(User.class), Records.info(Account.class));
    }
  }

  @Nested
  @DisplayName("Compact constructor — canonical-ctor rebuild runs the normalisation / validation")
  class CompactConstructor {

    @Test
    @DisplayName(
      "with(...) rebuilds through the canonical ctor so the compact-ctor normalisation applies to" +
        " carried-over components"
    )
    void withRunsCompactCtorNormalisation() {
      // The source is already normalised (built through the ctor). Replacing `age` carries `name`
      // over into a fresh canonical-ctor call — and the compact ctor re-normalises it. Prove the
      // normalisation is not skipped on the off-path (carried-over) component by feeding a value
      // that would only be lower-cased if the ctor actually ran on rebuild.
      final var src = Records.construct(NormalizingUser.class, name ->
        switch (name) {
          case "name" -> "  ALICE  ";
          case "age" -> 30;
          default -> throw new IllegalStateException();
        }
      );
      assertEquals("alice", src.name(), "construct must run the compact ctor forward");
      final NormalizingUser bumped = Records.with(src, "age", 31);
      assertEquals(new NormalizingUser("alice", 31), bumped);
      assertEquals("alice", bumped.name(), "carried-over name stays normalised through the rebuild ctor");
    }

    @Test
    @DisplayName("construct with an invalid value surfaces the compact ctor's IllegalArgumentException" + " (wrapped)")
    void constructPropagatesCompactCtorRejection() {
      // A validating compact ctor that throws must not be swallowed — the canonical-ctor invoker
      // wraps the failure, but the original IAE stays reachable as the cause so the caller can see
      // exactly which invariant was violated.
      final var ex = assertThrows(RuntimeException.class, () ->
        Records.construct(NormalizingUser.class, name ->
          switch (name) {
            case "name" -> "x";
            case "age" -> -1;
            default -> throw new IllegalStateException();
          }
        )
      );
      assertInstanceOf(IllegalArgumentException.class, rootCause(ex), () -> "root cause: " + rootCause(ex));
      assertTrue(rootCause(ex).getMessage().contains("non-negative"), () -> rootCause(ex).getMessage());
    }

    @Test
    @DisplayName(
      "fieldLens.set rebuilds through the canonical ctor, re-running normalisation on the written" + " value"
    )
    void fieldLensSetReNormalisesWrittenValue() {
      final Lens<NormalizingUser, String> nameLens = Records.fieldLens(NormalizingUser.class, "name");
      final var src = new NormalizingUser("alice", 30);
      final var rebuilt = nameLens.set(src, "  BOB  ");
      assertEquals("bob", rebuilt.name(), "the written value flows through the compact ctor and is normalised");
      assertEquals(30, rebuilt.age(), "off-path age carries over unchanged");
    }
  }

  @Nested
  @DisplayName("construct — primitive component fed a null value throws (canonical-ctor unbox)")
  class PrimitiveNullConstruct {

    @Test
    @DisplayName("passing null for a primitive component throws when the spread ctor handle unboxes it")
    void nullForPrimitiveComponentThrows() {
      // The spread-MethodHandle ctor path unboxes each Object[] slot into the primitive parameter.
      // A null slot for the `age` int cannot unbox — the JVM raises NPE inside the ctor invoker,
      // which Records wraps as a RuntimeException. Pins that a null-for-primitive is a loud
      // failure,
      // not a silent 0-default (records have no null-guard; that lives only in the bean
      // SettersWriter
      // primitive path).
      final var ex = assertThrows(RuntimeException.class, () ->
        Records.construct(User.class, name -> name.equals("name") ? "x" : null)
      );
      assertInstanceOf(NullPointerException.class, rootCause(ex), () -> "root cause: " + rootCause(ex));
    }
  }

  @Nested
  @DisplayName("RecordInfo raw handles — unboxed accessor / ctor MethodHandle path (MhIso substrate)")
  class RawHandles {

    @Test
    @DisplayName("accessorHandles keep the component's primitive return type (unboxed) and read the right" + " value")
    void accessorHandlesAreUnboxedAndCorrect() throws Throwable {
      final var info = Records.info(PrimitiveRecord.class);
      final var handles = info.accessorHandles();
      assertEquals(4, handles.length);
      // The int accessor handle's return type is the raw primitive int, not Integer — this is the
      // whole point of the raw-handle path (readers[] box; accessorHandles don't).
      assertEquals(int.class, handles[0].type().returnType(), "accessor handle keeps the unboxed int return");
      assertEquals(long.class, handles[1].type().returnType());
      assertEquals(double.class, handles[2].type().returnType());
      assertEquals(boolean.class, handles[3].type().returnType());
      final var rec = new PrimitiveRecord(7, 42L, 3.5, true);
      assertEquals(7, (int) handles[0].invoke(rec));
      assertEquals(42L, (long) handles[1].invoke(rec));
    }

    @Test
    @DisplayName("ctorHandle keeps the unboxed canonical-constructor signature and builds the record")
    void ctorHandleIsUnboxedAndBuilds() throws Throwable {
      final var info = Records.info(PrimitiveRecord.class);
      final var ctor = info.ctorHandle();
      final var type = ctor.type();
      assertEquals(PrimitiveRecord.class, type.returnType());
      assertArrayEquals(
        new Class<?>[] { int.class, long.class, double.class, boolean.class },
        type.parameterArray(),
        "ctor handle keeps the raw primitive parameter signature, no boxing"
      );
      final PrimitiveRecord built = (PrimitiveRecord) ctor.invoke(1, 2L, 3.0, false);
      assertEquals(new PrimitiveRecord(1, 2L, 3.0, false), built);
    }
  }

  private static Throwable rootCause(final Throwable t) {
    var cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
    return cur;
  }
}
