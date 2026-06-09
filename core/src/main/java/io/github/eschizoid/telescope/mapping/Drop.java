package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Drop-source-field row from {@link Mapping#drop(Accessor)} / {@link Mapping#drop(Accessor,
 * Class)}. Marks a single source field as intentionally NOT mapped to the target so the strict
 * deep-mapping factory accepts the pair without requiring a same-name target property.
 *
 * <p>Used when one side of a record↔bean pair carries fields the other shouldn't see (e.g. internal
 * metadata that mustn't leak across a partner-facing boundary). The single-arg variant scopes the
 * drop to the top-level mapper; the two-arg variant scopes it to a specific nested pair anywhere in
 * the recursion (analogous to {@link Via} carrying both accessors).
 *
 * <p>Package-private — users construct via the {@link Mapping#drop(Accessor)} / {@link
 * Mapping#drop(Accessor, Class)} factories and never see this type at the call site.
 */
public record Drop<A, B, X>(Accessor<A, X> src, Class<B> explicitTarget) implements Mapping<A, B>, MappingInternals<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.implClassOf(src);
  }

  /**
   * Explicit target class if the user supplied one (two-arg factory), otherwise {@code null} so
   * {@link DeepMap} binds the drop to the top-level mapper's target.
   */
  @Override
  public Class<B> targetClass() {
    return explicitTarget;
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
