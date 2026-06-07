package io.github.eschizoid.telescope.mapping;

/**
 * Package-private sibling of {@link Mapping}. Carries the {@code SerializedLambda} recovery
 * machinery — declaring classes and method names of the row's accessors — that {@link DeepMap}
 * needs to key overrides by {@code (sourceClass, targetClass)} type pair.
 *
 * <p>Split off the public {@link Mapping} interface so these reflective-recovery details don't leak
 * into v1.0's public surface. All three permitted {@link Mapping} record impls ({@link
 * SameTypedTo}, {@link TypedTransformTo}, {@link Via}) also implement this interface; {@link
 * DeepMap} casts a {@code Mapping<?, ?>} to {@code MappingInternals<?, ?>} when it needs the
 * recovery info.
 */
sealed interface MappingInternals<A, B> permits SameTypedTo, TypedTransformTo, Via {
  /**
   * Source class this row keys against (the declaring class of the source accessor, recovered via
   * {@code SerializedLambda}). Used by {@link DeepMap} to decide which type pairs this override
   * applies to.
   */
  Class<A> sourceClass();

  /** Target class this row keys against — declaring class of the target accessor. */
  Class<B> targetClass();

  /** Source record component name this row claims (the source accessor's method name). */
  String sourceField();

  /** Target record component name this row claims (the target accessor's method name). */
  String targetField();
}
