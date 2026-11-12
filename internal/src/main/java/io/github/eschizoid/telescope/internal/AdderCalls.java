package io.github.eschizoid.telescope.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Every call the adders strategy makes to an adder, on the runtime writer and in generated code, so
 * that {@code into} can build the value it reads from without running any adder on it.
 *
 * <p>{@code into} converts the source through {@code forward}, which builds a bean it then throws
 * away. Run through its adders, that bean would call each adder once and the target a second time,
 * and an adder that changes or drops what it is handed would leave the target holding something
 * {@code forward} never gives. While {@link #deferring} builds the value, the adders of the class
 * it names are not called: their elements are recorded instead. The elements recorded for the value
 * built are handed to {@code into}, which passes each to the target's adder; every other object of
 * that class built on the way, such as a nested child, has its adders called once the build ends.
 */
public final class AdderCalls {

  private AdderCalls() {}

  /** The deferral armed on this thread, or null. */
  private static final ThreadLocal<Deferral> ARMED = new ThreadLocal<>();

  /**
   * Passes each of {@code elements} to {@code adder} on {@code owner}, or records them where a
   * deferral is armed for {@code owner}'s class. A null {@code elements} adds nothing.
   */
  public static void add(
    final Object owner,
    final String property,
    final Iterable<?> elements,
    final BiConsumer<Object, Object> adder
  ) {
    if (elements == null) return;
    final var armed = ARMED.get();
    if (armed == null) {
      for (final var element : elements) adder.accept(owner, element);
      return;
    }
    if (armed.owner.isInstance(owner)) {
      armed.record(owner, property, elements, adder);
      return;
    }
    // An adder of another class runs now, and with no deferral armed: a conversion it starts, such
    // as a forward to the deferred class, builds a value of its own, which has its adders called as
    // it is built rather than once the outer build ends.
    ARMED.remove();
    try {
      for (final var element : elements) adder.accept(owner, element);
    } finally {
      ARMED.set(armed);
    }
  }

  /**
   * What {@link #deferring} built and the elements recorded for it: by property, the collection
   * each adder of the value built would have been handed.
   *
   * @param <T> the type built
   */
  public record Deferred<T>(T value, Map<String, Object> elements) {}

  /**
   * Builds a value through {@code build} with no adder of an instance of {@code owner} called. The
   * value built comes back with the elements its adders were to be handed; every other instance of
   * {@code owner} built on the way has its adders called, in the order they were reached, before
   * this returns.
   */
  public static <T> Deferred<T> deferring(final Class<?> owner, final Supplier<T> build) {
    final var previous = ARMED.get();
    final var deferral = new Deferral(owner);
    ARMED.set(deferral);
    final T value;
    try {
      value = build.get();
    } finally {
      ARMED.set(previous);
    }
    for (final var call : deferral.calls) {
      if (call.owner() != value) {
        for (final var element : call.elements()) call.adder().accept(call.owner(), element);
      }
    }
    final var elements = deferral.byOwner.get(value);
    return new Deferred<>(value, elements == null ? Map.of() : elements);
  }

  /** One recorded call: the elements an adder of an owner was to be handed. */
  private record Call(Object owner, Iterable<?> elements, BiConsumer<Object, Object> adder) {}

  /** The calls recorded while one deferral is armed. */
  private static final class Deferral {

    private final Class<?> owner;
    private final List<Call> calls = new ArrayList<>();
    private final Map<Object, Map<String, Object>> byOwner = new IdentityHashMap<>();

    Deferral(final Class<?> owner) {
      this.owner = owner;
    }

    void record(
      final Object built,
      final String property,
      final Iterable<?> elements,
      final BiConsumer<Object, Object> adder
    ) {
      calls.add(new Call(built, elements, adder));
      byOwner.computeIfAbsent(built, ignored -> new HashMap<>()).put(property, elements);
    }
  }
}
