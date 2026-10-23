package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.internal.Beans;

/**
 * The conversion a {@link BridgeFn} class names, built through its no-argument constructor when the
 * row is, as the bridge generated from the same row builds it when the bridge class is initialised,
 * so a constructor that throws stops both paths at the same point: where the mapper is declared.
 */
final class ConversionClass<X, Y> {

  private final Class<? extends BridgeFn<X, Y>> type;
  private final BridgeFn<X, Y> built;

  ConversionClass(final Class<? extends BridgeFn<X, Y>> type) {
    this.type = type;
    this.built = build();
  }

  Y forward(final X value) {
    return fn().forward(value);
  }

  X backward(final Y value) {
    return fn().backward(value);
  }

  private BridgeFn<X, Y> fn() {
    return built;
  }

  @SuppressWarnings("unchecked")
  private BridgeFn<X, Y> build() {
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
    return (BridgeFn<X, Y>) constructor.get();
  }
}
