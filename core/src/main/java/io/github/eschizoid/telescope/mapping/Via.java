package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.optics.Iso;

/**
 * Nested-mapper correspondence row from {@link Mapping#via(Accessor, Accessor, Mapper)}. The
 * contributed leaf-level {@link Iso} wraps the nested {@link Mapper}'s forward/backward pair —
 * preserving any custom rules the user baked into that mapper. The accessor / mapper type
 * parameters are erased ({@code ?}) because the same {@code via(...)} call can either supply a
 * matching-shape mapper or an element-level mapper that telescope lifts through the accessor's
 * container shape at row-processing time (see {@code DeepMap.fieldIsoOf}).
 *
 * <p>Package-private — users construct via {@link Mapping#via(Accessor, Accessor, Mapper)} and
 * never see this type at the call site.
 */
record Via<A, B>(Accessor<A, ?> src, Accessor<B, ?> tgt, Mapper<?, ?> nested)
  implements Mapping<A, B>, MappingInternals<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.implClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.implClassOf(tgt);
  }

  @Override
  public String sourceField() {
    return LambdaIntrospection.methodNameOf(src);
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgt);
  }

  /**
   * The element-level {@link Iso} the user-supplied mapper produces. {@link
   * io.github.eschizoid.telescope.mapping.DeepMap DeepMap} consumes this and, when the row's
   * source/target field types are container shapes matching the mapper's element classes, lifts
   * the Iso through the matching container (list / set / optional / map values).
   */
  @SuppressWarnings("unchecked")
  Iso<?, ?> elementIso() {
    final var raw = (Mapper<Object, Object>) nested;
    return Iso.of(raw::forward, raw::backward);
  }

  /** The mapper's source class — used by {@code DeepMap} to decide whether to auto-lift. */
  Class<?> mapperSourceClass() {
    return nested.sourceClass();
  }

  /** The mapper's target class — used by {@code DeepMap} to decide whether to auto-lift. */
  Class<?> mapperTargetClass() {
    return nested.targetClass();
  }
}
