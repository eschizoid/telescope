package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.optics.Iso;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct contract tests for {@link MhIso} — the MethodHandle-combinator conversion leaf. Covers all
 * four shape combinations of the read side (record vs bean getter) and the construct side (record
 * canonical constructor vs bean setter fold), plus the build-time capability gate and the semantics
 * the array leaf established: null-in/null-out, primitive slots flowing through the fold, identity
 * passthrough, non-identity per-slot Iso, and the {@code sp < 0} constant/compute slot.
 */
class MhIsoTest {

  record RecUser(String name, int age) {}

  static final class BeanUser {

    private String name;
    private int age;

    public BeanUser() {}

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

  /**
   * No-arg constructor but no setter for {@code name} — the field-injection shape MhIso declines.
   */
  static final class GetterOnlyBean {

    private final String name = "fixed";

    public GetterOnlyBean() {}

    public String getName() {
      return name;
    }
  }

  /**
   * Fluent / chained setters returning {@code this} (Lombok {@code @Accessors(chain=true)},
   * builder-style beans) — the non-void setter shape the void-only fold breaks on.
   */
  static final class FluentBeanUser {

    private String name;
    private int age;

    public FluentBeanUser() {}

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public FluentBeanUser setName(final String name) {
      this.name = name;
      return this;
    }

    public FluentBeanUser setAge(final int age) {
      this.age = age;
      return this;
    }
  }

  private static BeanUser bean(final String name, final int age) {
    final var b = new BeanUser();
    b.setName(name);
    b.setAge(age);
    return b;
  }

  // Identity passthrough for the two same-name/same-type slots (name, age), positions aligned.
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <S, T> Iso<S, T> identityPair(final Class<S> source, final Class<T> target) {
    final Iso<Object, Object> identity = Iso.identity();
    final int[] fwd = { 0, 1 };
    final int[] bwd = { 0, 1 };
    final Iso<Object, Object>[] fwdIso = new Iso[] { identity, identity };
    final Iso<Object, Object>[] bwdIso = new Iso[] { identity, identity };
    return MhIso.pair(source, target, fwd, fwdIso, bwd, bwdIso, identity);
  }

  @Nested
  @DisplayName("supports — build-time capability gate")
  class Supports {

    @Test
    @DisplayName("both records within arity are supported")
    void recordRecord() {
      assertTrue(MhIso.supports(RecUser.class, RecUser.class));
    }

    @Test
    @DisplayName("bean with no-arg ctor and full setter coverage is supported both directions")
    void beanEitherSide() {
      assertTrue(MhIso.supports(BeanUser.class, RecUser.class));
      assertTrue(MhIso.supports(RecUser.class, BeanUser.class));
      assertTrue(MhIso.supports(BeanUser.class, BeanUser.class));
    }

    @Test
    @DisplayName("a bean with a getter-only property is declined (routes to the array leaf)")
    void getterOnlyDeclined() {
      assertFalse(MhIso.supports(BeanUser.class, GetterOnlyBean.class));
      assertFalse(MhIso.supports(GetterOnlyBean.class, BeanUser.class));
    }
  }

  @Nested
  @DisplayName("conversion — the four read/construct shape combinations")
  class Conversion {

    @Test
    @DisplayName("bean to record and back, primitive age unboxed through the canonical constructor")
    void beanToRecord() {
      final Iso<BeanUser, RecUser> iso = identityPair(BeanUser.class, RecUser.class);
      final var r = iso.to(bean("ann", 30));
      assertEquals(new RecUser("ann", 30), r);
      final var back = iso.from(r);
      assertEquals("ann", back.getName());
      assertEquals(30, back.getAge());
    }

    @Test
    @DisplayName("record to bean via the setter fold, primitive age unboxed into setAge(int)")
    void recordToBean() {
      final Iso<RecUser, BeanUser> iso = identityPair(RecUser.class, BeanUser.class);
      final var b = iso.to(new RecUser("bob", 42));
      assertEquals("bob", b.getName());
      assertEquals(42, b.getAge());
      assertEquals(new RecUser("bob", 42), iso.from(b));
    }

    @Test
    @DisplayName("bean to bean round-trips through getters and the setter fold")
    void beanToBean() {
      final Iso<BeanUser, BeanUser> iso = identityPair(BeanUser.class, BeanUser.class);
      final var out = iso.to(bean("cara", 25));
      assertEquals("cara", out.getName());
      assertEquals(25, out.getAge());
    }

    @Test
    @DisplayName("record to a fluent-setter bean folds through chained setters that return this")
    void recordToFluentBean() {
      assertTrue(MhIso.supports(RecUser.class, FluentBeanUser.class));
      final Iso<RecUser, FluentBeanUser> iso = identityPair(RecUser.class, FluentBeanUser.class);
      final var b = iso.to(new RecUser("eve", 51));
      assertEquals("eve", b.getName());
      assertEquals(51, b.getAge());
      assertEquals(new RecUser("eve", 51), iso.from(b));
    }
  }

  @Nested
  @DisplayName("preserved array-leaf semantics")
  class Semantics {

    @Test
    @DisplayName("null in yields null out in both directions of a bean target")
    void nullInNullOut() {
      final Iso<RecUser, BeanUser> iso = identityPair(RecUser.class, BeanUser.class);
      assertNull(iso.to(null));
      assertNull(iso.from(null));
    }

    @Test
    @DisplayName("a non-identity per-slot Iso is applied on the way into a bean setter")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void nonIdentitySlotIntoBean() {
      final Iso<Object, Object> identity = Iso.identity();
      // name slot upper-cases forward / lower-cases backward; age stays identity.
      final Iso<Object, Object> upper = Iso.of(
        v -> v == null ? null : ((String) v).toUpperCase(),
        v -> v == null ? null : ((String) v).toLowerCase()
      );
      final int[] fwd = { 0, 1 };
      final int[] bwd = { 0, 1 };
      final Iso<Object, Object>[] fwdIso = new Iso[] { upper, identity };
      final Iso<Object, Object>[] bwdIso = new Iso[] { upper, identity };
      final Iso<RecUser, BeanUser> iso = MhIso.pair(RecUser.class, BeanUser.class, fwd, fwdIso, bwd, bwdIso, identity);
      final var b = iso.to(new RecUser("dan", 7));
      assertEquals("DAN", b.getName());
      assertEquals(7, b.getAge());
      assertEquals(new RecUser("dan", 7), iso.from(b));
    }

    @Test
    @DisplayName("a source-less (sp < 0) slot still applies its Iso, producing a constant from null")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void sourcelessSlotStillAppliesIso() {
      final Iso<Object, Object> identity = Iso.identity();
      // The name slot has no source (sp = -1) and a constant Iso that ignores its input and yields
      // a fixed value — the array leaf's iso.to(null) contract, which MhIso must not regress.
      final Iso<Object, Object> constant = Iso.of(ignored -> "CONST", ignored -> null);
      final int[] fwd = { -1, 1 };
      final int[] bwd = { 0, 1 };
      final Iso<Object, Object>[] fwdIso = new Iso[] { constant, identity };
      final Iso<Object, Object>[] bwdIso = new Iso[] { identity, identity };
      final Iso<RecUser, BeanUser> iso = MhIso.pair(RecUser.class, BeanUser.class, fwd, fwdIso, bwd, bwdIso, identity);
      final var b = iso.to(new RecUser("ignored", 9));
      assertEquals("CONST", b.getName());
      assertEquals(9, b.getAge());
    }

    @Test
    @DisplayName("a non-identity Iso yielding null into a primitive bean setter is skipped, leaving the default")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void nullTransformIntoPrimitiveBeanSkipped() {
      final Iso<Object, Object> identity = Iso.identity();
      // The age slot's forward always yields null. The array leaf's SettersWriter skips a null into
      // a
      // primitive setter, leaving the JLS default (0); unboxing null would NPE. MhIso must match.
      final Iso<Object, Object> nullify = Iso.of(ignored -> null, ignored -> 0);
      final int[] fwd = { 0, 1 };
      final int[] bwd = { 0, 1 };
      final Iso<Object, Object>[] fwdIso = new Iso[] { identity, nullify };
      final Iso<Object, Object>[] bwdIso = new Iso[] { identity, identity };
      final Iso<RecUser, BeanUser> iso = MhIso.pair(RecUser.class, BeanUser.class, fwd, fwdIso, bwd, bwdIso, identity);
      final var b = iso.to(new RecUser("zoe", 99));
      assertEquals("zoe", b.getName());
      assertEquals(0, b.getAge()); // skipped → JLS default, not an NPE
    }

    @Test
    @DisplayName("an Error thrown during conversion propagates unwrapped, not masked as a RuntimeException")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void errorPropagatesUnwrapped() {
      final Iso<Object, Object> identity = Iso.identity();
      // A slot Iso that throws an Error mid-conversion. The array leaf has no try/catch and lets
      // Errors through raw; MhIso must not mask it as a RuntimeException.
      final Iso<Object, Object> boom = Iso.of(
        ignored -> {
          throw new StackOverflowError("boom");
        },
        ignored -> null
      );
      final int[] fwd = { 0, 1 };
      final int[] bwd = { 0, 1 };
      final Iso<Object, Object>[] fwdIso = new Iso[] { boom, identity };
      final Iso<Object, Object>[] bwdIso = new Iso[] { identity, identity };
      final Iso<RecUser, BeanUser> iso = MhIso.pair(RecUser.class, BeanUser.class, fwd, fwdIso, bwd, bwdIso, identity);
      assertThrows(StackOverflowError.class, () -> iso.to(new RecUser("x", 1)));
    }
  }
}
