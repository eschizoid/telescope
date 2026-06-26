package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope;

/**
 * Forward-only literal correspondence from {@link Mapping#constant(Telescope.Accessor, Object)
 * Mapping.constant(Accessor, X)} and {@link Mapping#constant(Telescope, Object)
 * Mapping.constant(Telescope, X)}. Stamps the captured value at the target location each forward
 * call.
 *
 * <p>The target is always a {@link Telescope} — the flat factory {@code constant(Accessor, X)}
 * wraps the bare accessor in a single-hop telescope at construction time so the engine has one
 * uniform handler. Users see two overloads in the public API; internally there's one shape, one
 * fixup path, one set of laws.
 *
 * <p>Backward direction is a no-op (the rebuilt source carries the type default at the dual slot —
 * same retraction semantics as {@link Drop} on the source side). Intermediate hops without a
 * same-name source counterpart are allocated as a recursive default-tree (records only); see {@link
 * Mapping#to(io.github.eschizoid.telescope.Telescope.Accessor, Telescope) Mapping.to(Accessor,
 * Telescope)} for the same allocation behavior.
 *
 * <p>Package-private — users construct via {@link Mapping#constant Mapping.constant} and never see
 * this type at the call site. The engine in {@link DeepMap} routes this row through the
 * telescope-fixup machinery.
 */
public record Constant<A, B, X>(Telescope<B, X> targetTelescope, X value) implements Mapping<A, B> {
  /** Returns {@code null} — no source-side accessor; outer-pair pinning. */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  /** Returns {@code null} — {@code Telescope<B, X>} doesn't carry its root class at runtime. */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  /** Returns {@code null} — no source-side field. */
  @Override
  public String sourceField() {
    return null;
  }

  /** Returns {@code null} — top-level target field is recovered via the telescope's first hop. */
  @Override
  public String targetField() {
    return null;
  }
}
