package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope;

/**
 * Both-nested correspondence row from {@link Mapping#to(Telescope, Telescope)} (broadcast) and
 * {@link Mapping#zip(Telescope, Telescope)} (positional). Source and target sides are multi-hop
 * {@link Telescope}s with a shared leaf type {@code X}.
 *
 * <p>The {@link DeepMap} engine applies this row at the outer {@code (sourceClass, targetClass)}
 * pair only — both class fields come back {@code null} since neither side carries its root class at
 * runtime. Top-level field claim is recovered from each telescope's {@code firstHopName()}. The
 * engine dispatches on {@link #kind()}:
 *
 * <ul>
 *   <li>{@link Kind#BROADCAST} — forward overlay: {@code tgtTelescope.set(b,
 *       srcTelescope.read(a))}. Backward overlay: {@code srcTelescope.set(a,
 *       tgtTelescope.read(b))}. When either side is many-focus, the lattice's intrinsic broadcast /
 *       first-focus semantics apply — no extra machinery.
 *   <li>{@link Kind#ZIP} — forward fixup: read all values via {@link Telescope#toList} on the
 *       source, then write positionally via {@link Telescope#updateIndexed} on the target with
 *       cardinality enforcement (mismatch throws). Backward fixup mirrors.
 * </ul>
 *
 * <p>Internal — users construct via {@link Mapping#to(Telescope, Telescope)} or {@link
 * Mapping#zip(Telescope, Telescope)} and never see this type at the call site.
 */
public record TelescopeToTelescope<A, B, X>(
  Telescope<A, X> sourceTelescope,
  Telescope<B, X> targetTelescope,
  Kind kind
) implements Mapping<A, B>, MappingInternals<A, B> {
  /** Discriminates broadcast vs positional-zip semantics. */
  public enum Kind {
    /** Single-value semantics: {@code Telescope.set} broadcasts on a many-focus target. */
    BROADCAST,
    /**
     * Positional N:N: cardinality-enforced zip via {@code Telescope.toList} + {@code
     * updateIndexed}.
     */
    ZIP,
  }

  /** Returns {@code null} — see class-level javadoc on outer-pair pinning. */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  /** Returns {@code null} — see class-level javadoc on outer-pair pinning. */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  /** Returns {@code null} — no top-level source field to claim against. */
  @Override
  public String sourceField() {
    return null;
  }

  /** Returns {@code null} — no top-level target field to claim against. */
  @Override
  public String targetField() {
    return null;
  }
}
