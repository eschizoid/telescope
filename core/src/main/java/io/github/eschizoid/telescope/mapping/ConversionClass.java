package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.internal.Beans;

/**
 * The conversion a {@link BridgeFn} class names, built through its no-argument constructor the
 * first time the row converts a value. A mapper that runs the bridge generated from the same row
 * never converts through it, so a row naming a class costs nothing until the runtime path uses it.
 */
final class ConversionClass<X, Y> {

  private final Class<? extends BridgeFn<X, Y>> type;
  private volatile BridgeFn<X, Y> built;

  ConversionClass(final Class<? extends BridgeFn<X, Y>> type) {
    this.type = type;
  }

  Y forward(final X value) {
    return fn().forward(value);
  }

  X backward(final Y value) {
    return fn().backward(value);
  }

  @SuppressWarnings("unchecked")
  private BridgeFn<X, Y> fn() {
    var fn = built;
    if (fn != null) return fn;
    final var constructor = Beans.noArgConstructor(type);
    if (constructor == null) {
      throw new IllegalArgumentException(
        "Mapping.to(src, tgt, " +
          type.getSimpleName() +
          ".class): " +
          type.getName() +
          " declares no constructor taking no arguments, so the row cannot build its conversion." +
          " Add one, or pass an instance: to(src, tgt, new " +
          type.getSimpleName() +
          "(...))"
      );
    }
    fn = (BridgeFn<X, Y>) constructor.get();
    built = fn;
    return fn;
  }
}
