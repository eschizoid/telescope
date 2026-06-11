package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.DeepMap;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.function.Supplier;

/**
 * Lazy-supplier correspondence row from {@link Mapping#compute(Accessor, Supplier)}. Calls the
 * supplier at every forward-apply, stamping the result onto a target field; the source side has no
 * slot, so {@link DeepMap}'s backward direction silently drops it (the rebuilt source carries the
 * type default at the dual slot — same retraction semantics as {@link Drop} but on the target
 * side).
 *
 * <p>Use for values that must be freshly evaluated each forward call: timestamps ({@code
 * Instant::now}), per-call generated IDs ({@code UUID::randomUUID}), and fresh mutable containers
 * ({@code HashMap::new} / {@code ArrayList::new}) where a literal would share one reference across
 * every call (a Java mutable-default-argument trap). For fixed values that ARE safe to share, use
 * {@link Constant}.
 *
 * <p>Forward-only by design. Mirrors MapStruct's {@code @Mapping(expression = "java(...)")} but
 * stays in plain Java — the supplier is a typed {@link Supplier} that {@code javac} type-checks
 * against the target leaf, no string-templated body to parse. Declared once in the same {@code
 * Telescope.mapper(...)} call as the other rows.
 *
 * <p>Package-private — users construct via {@link Mapping#compute(Accessor, Supplier)} and never
 * see this type at the call site.
 */
public record Compute<A, B, X>(
  Accessor<B, X> tgtAccessor,
  Supplier<? extends X> supplier
) implements Mapping<A, B>, MappingInternals<A, B> {
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
