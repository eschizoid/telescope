package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import java.util.function.Function;

/**
 * The row {@link Mapping#compute(Telescope.Accessor, Function) Mapping.compute(Accessor, Function)}
 * builds: a target property filled, on every forward call, with what {@code function} makes of the
 * source. The flat accessor is held as a single-hop telescope, as {@link Compute} holds it, so the
 * engine writes both rows through one path.
 *
 * <p>The row names no source property: it reads the whole source, so it claims none, and every
 * source property pairs by name as it would without the row. Backward and patch skip the target
 * property it fills, as they skip a {@link Compute} row's.
 *
 * <p>Internal: users build the row with {@code compute(...)} and never name this type.
 */
public record ComputeFromSource<A, B, X>(
  Telescope<B, X> targetTelescope,
  Function<? super A, ? extends X> function
) implements Mapping<A, B> {
  /** Returns {@code null}: the row is keyed to the mapper's own pair, as a {@link Compute} is. */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  /** Returns {@code null}: {@code Telescope<B, X>} does not carry its root class at run time. */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  /** Returns {@code null}: the row reads the whole source, not one property of it. */
  @Override
  public String sourceField() {
    return null;
  }

  /** Returns {@code null}: the target property is the first hop of the target telescope. */
  @Override
  public String targetField() {
    return null;
  }
}
