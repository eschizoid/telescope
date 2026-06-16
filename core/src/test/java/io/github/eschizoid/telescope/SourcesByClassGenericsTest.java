package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the generic signature of {@link Sources#byClass(Class)} added in Enh 7. The change makes the
 * return type narrow to {@code T} via {@code Class<T>}, eliminating the cast at the call site.
 *
 * <p>Safety relies on the builder's invariant: entries are keyed by {@code source.getClass()}, so
 * the value stored under {@code Class<T>} is guaranteed to be an instance of {@code T}. This test
 * pins both the no-cast call site and backward compatibility with {@code Class<?>} arguments.
 */
class SourcesByClassGenericsTest {

  record Headers(String tenant) {}

  record Body(String payload) {}

  @Test
  @DisplayName("byClass(Class<T>) returns T directly — no cast required at call site")
  void byClassReturnsTypedValueWithoutCast() {
    final var sources = Sources.of(new Headers("tenant-a"), new Body("p"));
    // No cast — the generic return type narrows to Headers.
    final Headers headers = sources.byClass(Headers.class);
    assertEquals("tenant-a", headers.tenant());

    final Body body = sources.byClass(Body.class);
    assertEquals("p", body.payload());
  }

  @Test
  @DisplayName("byClass on absent class returns null (Class.cast(null) is null, not NPE)")
  void byClassAbsentReturnsNull() {
    final var sources = Sources.of(new Headers("t"));
    // Missing class: Class.cast(null) returns null, not NPE.
    final Body body = sources.byClass(Body.class);
    assertNull(body);
  }

  @Test
  @DisplayName("backward-compat: existing callers passing Class<?> still compile via capture inference")
  void backwardCompatRawClass() {
    final var sources = Sources.of(new Headers("t"));
    // Pre-Enh-7 callers wrote Class<?> at the call site. Now T captures the wildcard, returning
    // Object — still compiles, no narrowing benefit but no breakage either.
    final Class<?> raw = Headers.class;
    final Object obj = sources.byClass(raw);
    assertEquals(new Headers("t"), obj);
  }
}
