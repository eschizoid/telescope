package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Nested-target correspondence row from {@link Mapping#to(Accessor, Telescope)}. Stamps a flat
 * source value through a multi-hop {@link Telescope} on the target side — closes the gap with
 * MapStruct's {@code @Mapping(source = "flat", target = "a.b.c")} using the optics lattice's public
 * surface ({@code Telescope}) for the target read/write.
 *
 * <p>The {@link DeepMap} engine recognises this row at the <em>outer</em> {@code (sourceClass,
 * targetClass)} pair only (the pair from the enclosing {@link Telescope#mapper(Class, Class,
 * MapStep...)} call). It does not participate in the per-field claim assembly the way {@link
 * SameTypedTo} does; instead, after the base auto-mapping produces a target value, this row's
 * {@code targetTelescope.set(b, value)} overlays the leaf at the location the telescope resolves
 * to. The backward direction is the mirror: read at the target telescope, write to the source via
 * the accessor's lens.
 *
 * <p><b>Source field claim.</b> {@link #sourceField()} returns the source accessor's method name —
 * the engine treats this exactly like {@link SameTypedTo} for the source-must-be-claimed check.
 * {@link #targetField()} returns {@code null}: there is no top-level target field to claim, since
 * the telescope's leaf lives in a nested sub-object. The top-level target fields traversed by the
 * telescope (e.g. {@code B::getShipping}) are still auto-mapped from same-name source fields if
 * present; the overlay happens <em>after</em> that auto-mapping and overrides only the leaf.
 *
 * <p><b>Target class recovery.</b> The {@code Telescope<B, X>} doesn't carry its root class at
 * runtime (Java generics are erased). The engine relies on the enclosing mapper context: this row's
 * {@link #targetClass()} returns {@code null}, and {@link DeepMap} only applies the row at the
 * top-level pair supplied to {@link Telescope#mapper(Class, Class, MapStep...)}.
 *
 * <p>Internal — users construct via {@link Mapping#to(Accessor, Telescope)} and never see this type
 * at the call site.
 */
public record TelescopeTo<A, B, X>(
  Accessor<A, X> srcAccessor,
  Telescope<B, X> targetTelescope
) implements Mapping<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.implClassOf(srcAccessor);
  }

  /**
   * Returns {@code null} — the target side is a {@link Telescope} whose root class isn't
   * recoverable at runtime. The engine pins the row to the outer pair via the enclosing {@link
   * Telescope#mapper(Class, Class, MapStep...)} context.
   */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  @Override
  public String sourceField() {
    return LambdaIntrospection.methodNameOf(srcAccessor);
  }

  /**
   * Returns {@code null} — the telescope's leaf isn't a top-level target field, so there's no
   * top-level target field for this row to claim against.
   */
  @Override
  public String targetField() {
    return null;
  }
}
