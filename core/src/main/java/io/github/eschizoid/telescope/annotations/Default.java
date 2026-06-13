package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A null-coalescing default for a source field at {@link Bridge} codegen time. Mirrors MapStruct's
 * {@code @Mapping(target = "x", defaultValue = "literal")} and the runtime {@link
 * io.github.eschizoid.telescope.mapping.Mapping#toOrElse(io.github.eschizoid.telescope.Telescope.Accessor,
 * io.github.eschizoid.telescope.Telescope.Accessor, Object) Mapping.toOrElse(srcAcc, tgtAcc,
 * defaultValue)} factory.
 *
 * <p>Forward direction: if {@code source.field() == null}, the generated bridge substitutes
 * {@link #value()} as the literal expression — otherwise the source value passes through unchanged.
 * Backward direction is identity (whatever lands on the target round-trips back to the source slot
 * as itself, including the substituted default — same asymmetry the runtime form accepts).
 *
 * <pre>{@code
 * @Bridge(value = UserEntity.class, defaults = {
 *   @Default(field = "region", value = "EMEA"),
 *   @Default(field = "tier",   value = "FREE")
 * })
 * public record User(String id, String region, String tier) {}
 * }</pre>
 *
 * <p>{@code field} names the source side (the field whose null triggers the substitution). The
 * value must parse against the source field's declared type, using the same parser as {@link
 * Constant}: {@link String}, {@code int} / {@link Integer}, {@code long} / {@link Long}, the rest
 * of the numeric primitives, {@code boolean} / {@link Boolean}, {@code char} / {@link Character},
 * and the literal {@code "null"} for reference types.
 *
 * <h2>Strict-null only</h2>
 *
 * <p>This codegen form covers strict-null only — analogous to the 3-arg runtime {@code toOrElse}.
 * Predicate-gated coalescing (the 4-arg {@code toOrElse(src, tgt, default, predicate)} factory)
 * stays runtime-only because annotation attributes cannot hold lambda predicates.
 *
 * <p>Empty source fields ({@code ""}, {@link java.util.Collections#emptyList()}, etc.) are NOT
 * treated as null — only {@code source == null} triggers the substitution. For empty-collection or
 * empty-string handling, use the runtime {@code toOrElse(src, tgt, default, Predicate)} factory.
 *
 * <h2>Empty source field types</h2>
 *
 * <p>Primitive source fields cannot be {@code null} in Java; declaring a {@code @Default} on a
 * primitive-typed source field is a compile error. Use the field's wrapper type or rework the
 * source shape if the slot can be absent.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface Default {
  /**
   * Source field name whose null value triggers the substitution. Must name a real field on the
   * {@link Bridge} source.
   */
  String field();

  /**
   * The fallback literal as a string. Parsed at codegen time against the source field's declared
   * type, using the same parser as {@link Constant}.
   */
  String value();
}
