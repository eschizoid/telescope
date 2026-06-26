package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import java.util.function.Supplier;

/**
 * Forward-only lazy-supplier correspondence from {@link Mapping#compute(Telescope.Accessor,
 * Supplier) Mapping.compute(Accessor, Supplier)} and {@link Mapping#compute(Telescope, Supplier)
 * Mapping.compute(Telescope, Supplier)}. Invokes the supplier once per forward call and stamps the
 * result at the target location.
 *
 * <p>The target is always a {@link Telescope} — the flat factory {@code compute(Accessor,
 * Supplier)} wraps the bare accessor in a single-hop telescope at construction time so the engine
 * has one uniform handler. One shape, one fixup path, one set of laws.
 *
 * <p>Backward direction is a no-op. Same intermediate-allocation behavior as {@link Constant}:
 * record-typed hops without a same-name source counterpart are allocated as a recursive
 * default-tree so the supplier-stamped value can land at any depth.
 *
 * <p>Package-private — users construct via {@link Mapping#compute Mapping.compute} and never see
 * this type at the call site.
 */
public record Compute<A, B, X>(
  Telescope<B, X> targetTelescope,
  Supplier<? extends X> supplier
) implements Mapping<A, B> {
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
