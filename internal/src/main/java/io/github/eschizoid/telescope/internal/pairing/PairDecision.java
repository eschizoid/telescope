package io.github.eschizoid.telescope.internal.pairing;

/**
 * The outcome of {@link PairingRules#decidePair} for one (source type, target type) field pair —
 * the world-agnostic decision the runtime turns into an {@code Iso} and the compile-time verifier
 * turns into a diagnostic (or a recursion). Exactly one decision per pair; {@link Incompatible}
 * carries the final diagnostic text so both worlds report identical messages.
 *
 * @param <T> the world's type handle
 */
public sealed interface PairDecision<T> {
  /** Same type on both sides, and not a container the allocation table rebuilds — identity. */
  record Identity<T>() implements PairDecision<T> {}

  /** Primitive ↔ wrapper pair over the same scalar — null-safe box/unbox. */
  record PrimitiveWrapper<T>() implements PairDecision<T> {}

  /**
   * Same-kind {@code Collection} pair whose elements need no conversion — element copy into a fresh
   * target instance. A pair of one declared type takes it too, so the target never shares the
   * source's container.
   */
  record CollectionCopy<T>() implements PairDecision<T> {}

  /**
   * Same-kind {@code Map} pair whose entries need no conversion — entry copy into a fresh target
   * instance. A pair of one declared type takes it too, so the target never shares the source's
   * map.
   */
  record MapCopy<T>() implements PairDecision<T> {}

  /**
   * Two different enums whose constants line up by name in every direction the mapper converts —
   * each constant converts to the constant of the same name on the other side, and null stays null.
   */
  record EnumByName<T>() implements PairDecision<T> {}

  /** Both sides reflectable (record or bean) — recurse into the nested pair. */
  record RecursePair<T>() implements PairDecision<T> {}

  /** Source {@code Optional<X>} to nullable target — element pair {@code (X, target)}. */
  record OptionalToNullable<T>(T elementSrc, T elementTgt) implements PairDecision<T> {}

  /** Nullable source to target {@code Optional<Y>} — element pair {@code (source, Y)}. */
  record NullableToOptional<T>(T elementSrc, T elementTgt) implements PairDecision<T> {}

  /** Same-kind containers — lift the element pair through the container. */
  record LiftContainer<T>(ContainerView<T> src, ContainerView<T> tgt) implements PairDecision<T> {}

  /** No compatible conversion — {@code message} is the diagnostic both worlds emit verbatim. */
  record Incompatible<T>(String message) implements PairDecision<T> {}
}
