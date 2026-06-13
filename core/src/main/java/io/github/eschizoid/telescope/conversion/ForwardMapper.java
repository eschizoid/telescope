package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.optics.Getter;

/**
 * A forward-only {@code A → B} mapper produced by {@link Telescope#mapperForward(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)}. The backward direction is not present at the
 * type level — there is no {@code backward(...)} method to call — so the "this mapper is one-way"
 * contract is enforced by the compiler rather than by a runtime throw.
 *
 * <p>Use this when the conversion is genuinely directional — entity → DTO write-only, audit-log
 * projection, normalisation pipeline — and the round-trip ergonomics of {@link Mapper} are not just
 * unused but actively misleading. MapStruct cannot express "this mapper is one-way" in its type
 * system; the typed escape valve here is exactly the differentiator the type system buys.
 *
 * <p>For bidirectional conversions, use {@link Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} and the regular {@link Mapper}.
 */
public final class ForwardMapper<A, B> {

  // Lattice-routed: the read substrate is the internal `Getter<A, B>` (the lattice's read-only
  // optic primitive — the same shape Mapper's bidirectional `Iso<A, B>` weakens to). Stored as
  // the optic, not a raw `Function`, so the lattice carries the field semantics — composition
  // via .then(...) below routes through Getter.then(Getter) rather than ad-hoc Function chains.
  private final Getter<A, B> forward;
  private final Class<A> sourceClass;
  private final Class<B> targetClass;

  ForwardMapper(final Getter<A, B> forward, final Class<A> sourceClass, final Class<B> targetClass) {
    this.forward = forward;
    this.sourceClass = sourceClass;
    this.targetClass = targetClass;
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> Cross-package factory used by {@link
   * Telescope#mapperForward(Class, Class, io.github.eschizoid.telescope.mapping.MapStep...)}. The
   * supplied {@link java.util.function.Function} is adapted to the lattice's {@link Getter}
   * substrate immediately — the lattice still owns the read shape, this factory is just the
   * cross-package adapter that the {@code Telescope} factory pipes through. External code must not
   * call this directly; use {@link Telescope#mapperForward(Class, Class,
   * io.github.eschizoid.telescope.mapping.MapStep...)}.
   */
  public static <A, B> ForwardMapper<A, B> create(
    final java.util.function.Function<? super A, ? extends B> forward,
    final Class<A> sourceClass,
    final Class<B> targetClass
  ) {
    final Getter<A, B> getter = forward::apply;
    return new ForwardMapper<>(getter, sourceClass, targetClass);
  }

  /** Forward conversion {@code A → B}. */
  public B forward(final A a) {
    return forward.get(a);
  }

  /** Alias of {@link #forward(Object)}. */
  public B read(final A a) {
    return forward.get(a);
  }

  /**
   * Compose with another forward-only projection: {@code this.then(next).forward(a) ==
   * next.forward(this.forward(a))}. Routes through the lattice's {@code Getter.then(Getter)}
   * composition — no ad-hoc function chains.
   *
   * <pre>{@code
   * ForwardMapper<Entity, Dto> entityToDto = Telescope.mapperForward(Entity.class, Dto.class, ...);
   * ForwardMapper<Dto, AuditEvent> dtoToAudit = Telescope.mapperForward(Dto.class, AuditEvent.class, ...);
   * ForwardMapper<Entity, AuditEvent> pipeline = entityToDto.then(dtoToAudit);
   * }</pre>
   */
  public <C> ForwardMapper<A, C> then(final ForwardMapper<B, C> next) {
    final Getter<A, C> composed = a -> next.forward.get(forward.get(a));
    return new ForwardMapper<>(composed, sourceClass, next.targetClass);
  }

  /** The mapper's source class. */
  public Class<A> sourceClass() {
    return sourceClass;
  }

  /** The mapper's target class. */
  public Class<B> targetClass() {
    return targetClass;
  }
}
