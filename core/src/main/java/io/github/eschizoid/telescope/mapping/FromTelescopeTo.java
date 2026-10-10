package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Nested-source correspondence row from {@link Mapping#to(Telescope, Accessor)}. Mirror of {@link
 * TelescopeTo}: source is a multi-hop {@link Telescope} on the {@code A} side, target is a flat
 * {@link Accessor} on the {@code B} side. Closes the gap with MapStruct's {@code @Mapping(source =
 * "a.b.c", target = "flat")}.
 *
 * <p>The {@link DeepMap} engine applies this row at the outer {@code (sourceClass, targetClass)}
 * pair only. Top-level target field claim: yes (recovered from the target accessor). Source field
 * claim: none — the path's leaf lives nested inside the source. Forward: after the base produces
 * {@code b}, rebuild it with the target field overridden by {@code sourceTelescope.read(a)}.
 * Backward: rebuild {@code a} with the source telescope's leaf overridden by the target accessor
 * read of {@code b}.
 *
 * <p>Internal — users construct via {@link Mapping#to(Telescope, Accessor)} and never see this type
 * at the call site.
 */
public record FromTelescopeTo<A, B, X>(
  Telescope<A, X> sourceTelescope,
  Accessor<B, X> tgtAccessor
) implements Mapping<A, B> {
  /**
   * Returns {@code null} — the source side is a {@link Telescope}, which names no pair of its own.
   * The engine pins the row to the outer mapper pair via {@code DeepMap.groupOverridesByPair}'s
   * top-source substitution, and keys its target side there too.
   */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  /**
   * The class the target accessor is applied to. The engine applies the row at the outer mapper
   * pair whatever this class is, and refuses it when the mapper's target neither is nor extends it.
   */
  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.receiverClassOf(tgtAccessor);
  }

  /**
   * Returns {@code null} — the source side's leaf isn't a top-level source field, so there's no
   * top-level source field for this row to claim against.
   */
  @Override
  public String sourceField() {
    return null;
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgtAccessor);
  }
}
