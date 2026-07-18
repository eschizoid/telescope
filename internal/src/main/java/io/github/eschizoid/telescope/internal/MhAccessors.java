package io.github.eschizoid.telescope.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Native-image accessor closures: one functional-interface shape per accessor kind, each a
 * source-level lambda closing over a {@link MethodHandle} adapted once with {@link
 * MethodHandle#asType(MethodType) asType}. This is the class-definition-free counterpart to the
 * {@link java.lang.invoke.LambdaMetafactory} path {@link Records} / {@link Beans} use on a stock
 * JVM — {@code LambdaMetafactory.metafactory(...)} synthesizes a class per accessor, which
 * native-image's closed world forbids at run time, whereas these lambda classes are compile-time.
 * The builders are chosen by {@link NativeImage#IN_IMAGE}; the JVM keeps the faster LMF path.
 *
 * <p>{@code asType} does the same receiver/return/parameter cast + primitive box/unbox the LMF
 * {@code instantiatedMethodType} bridge does, so a closure here is value-equivalent to the LMF
 * accessor it replaces. Each closure's {@link MethodHandle#invokeExact invokeExact} call-site types
 * match the adapted handle exactly (all {@code Object} / {@code void}), the requirement {@code
 * invokeExact} enforces. The target member must be registered for reflection in the image's
 * reachability metadata (the app supplies that for its own types).
 */
final class MhAccessors {

  private MhAccessors() {}

  /** {@code () -> R} (a no-arg factory / {@code builder()}) as a {@code Supplier<Object>}. */
  static Supplier<Object> supplier(final MethodHandle handle) {
    final var adapted = handle.asType(MethodType.methodType(Object.class));
    return () -> {
      try {
        return (Object) adapted.invokeExact();
      } catch (final Throwable t) {
        throw dispatchFailure(t);
      }
    };
  }

  /**
   * {@code (P) -> R} (a reader / getter / {@code build()}) as a {@code Function<Object, Object>}.
   */
  static Function<Object, Object> function(final MethodHandle handle) {
    final var adapted = handle.asType(MethodType.methodType(Object.class, Object.class));
    return obj -> {
      try {
        return (Object) adapted.invokeExact(obj);
      } catch (final Throwable t) {
        throw dispatchFailure(t);
      }
    };
  }

  /** {@code (P, V) -> void} (a void setter) as a {@code BiConsumer<Object, Object>}. */
  static BiConsumer<Object, Object> biConsumer(final MethodHandle handle) {
    final var adapted = handle.asType(MethodType.methodType(void.class, Object.class, Object.class));
    return (target, value) -> {
      try {
        adapted.invokeExact(target, value);
      } catch (final Throwable t) {
        throw dispatchFailure(t);
      }
    };
  }

  /**
   * {@code (P, V) -> R} (a fluent builder setter) as a {@code BiFunction<Object, Object, Object>}.
   */
  static BiFunction<Object, Object, Object> biFunction(final MethodHandle handle) {
    final var adapted = handle.asType(MethodType.methodType(Object.class, Object.class, Object.class));
    return (target, value) -> {
      try {
        return (Object) adapted.invokeExact(target, value);
      } catch (final Throwable t) {
        throw dispatchFailure(t);
      }
    };
  }

  // Preserve the raw exception: an unchecked ClassCastException / NullPointerException from a bad
  // value or null receiver propagates unwrapped (matching the LMF path); only a checked Throwable
  // is
  // wrapped.
  private static RuntimeException dispatchFailure(final Throwable t) {
    if (t instanceof Error e) throw e;
    if (t instanceof RuntimeException re) return re;
    return new IllegalStateException("native-image MethodHandle accessor dispatch failed", t);
  }
}
