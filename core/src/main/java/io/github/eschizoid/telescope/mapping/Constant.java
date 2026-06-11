package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Eager-literal correspondence row from {@link Mapping#constant(Accessor, Object)}. Stamps a fixed
 * value onto a target field at forward-apply time; the source-side has no slot for this value, so
 * {@link DeepMap}'s backward direction silently drops it (the rebuilt source carries the type
 * default at the dual slot — same retraction semantics as {@link Drop} but on the target side).
 *
 * <p>Use for tenant tags, schema versions, environment markers, and other values that should be the
 * same on every forward call. For values that need to be re-evaluated per call (timestamps, fresh
 * collections, IDs), use {@link Compute} instead — a literal {@code constant(Tgt::metadata, new
 * HashMap<>())} would share one map reference across every forward call.
 *
 * <p>Forward-only by design. Mirrors MapStruct's {@code @Mapping(constant = "...")}; the difference
 * is that the row lives next to the other field correspondences in the same {@code
 * Telescope.mapper(...)} call instead of being split across a second {@code @InheritInverse
 * Configuration} interface — declared once, semantically explicit.
 *
 * <p>Package-private — users construct via {@link Mapping#constant(Accessor, Object)} and never see
 * this type at the call site.
 */
public record Constant<A, B, X>(Accessor<B, X> tgtAccessor, X value) implements Mapping<A, B>, MappingInternals<A, B> {
  /** Returns {@code null} — no source-side accessor; the row is forward-only. */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.implClassOf(tgtAccessor);
  }

  /** Returns {@code null} — no source field to claim. */
  @Override
  public String sourceField() {
    return null;
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgtAccessor);
  }
}
