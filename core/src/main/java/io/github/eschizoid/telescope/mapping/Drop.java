package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Drop-source-field row from {@link Mapping#drop(Accessor)}. Marks a single source field as
 * intentionally NOT mapped to the target so the strict deep-mapping factory accepts the pair
 * without requiring a same-name target property. Used when one side of a record↔bean pair carries
 * fields the other shouldn't see (e.g. internal metadata that mustn't leak across a partner-facing
 * boundary).
 *
 * <p>Package-private — users construct via {@link Mapping#drop(Accessor)} and never see this type
 * at the call site.
 */
record Drop<A, B, X>(Accessor<A, X> src) implements Mapping<A, B>, MappingInternals<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.implClassOf(src);
  }

  /**
   * No target accessor — a drop row by definition doesn't claim a target field. Returning {@code
   * null} lets {@link DeepMap} short-circuit the (source, target) type-pair indexing path for this
   * row.
   */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  @Override
  public String sourceField() {
    return LambdaIntrospection.methodNameOf(src);
  }

  /** No target field — distinguishes a drop row from {@link SameTypedTo} / {@link Via}. */
  @Override
  public String targetField() {
    return null;
  }
}
