package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A literal value injected into a target field at {@link Bridge} codegen time. Mirrors MapStruct's
 * {@code @Mapping(target = "x", constant = "literal")}. Forward-only by design — the target field
 * is built from the literal; backward conversion silently drops the slot since the source has no
 * counterpart.
 *
 * <pre>{@code
 * @Bridge(value = LineItemEntity.class, constants = {
 *   @Constant(field = "source",  value = "API"),
 *   @Constant(field = "version", value = "1")
 * })
 * public record LineItem(String id, java.math.BigDecimal unitPrice) {}
 * }</pre>
 *
 * <p>The annotation value is a {@link String}. The processor parses it at emit time against the
 * target field's declared type. Supported field types:
 *
 * <ul>
 *   <li>{@link String} — emitted as a quoted string literal
 *   <li>{@code boolean} / {@link Boolean} — must be {@code "true"} or {@code "false"}
 *   <li>{@code int} / {@link Integer}, {@code long} / {@link Long}, {@code short} / {@link Short},
 *       {@code byte} / {@link Byte} — must parse via the type's {@code parseX(String)}
 *   <li>{@code double} / {@link Double}, {@code float} / {@link Float} — must parse via the type's
 *       {@code parseX(String)}
 *   <li>{@code char} / {@link Character} — must be a single character
 *   <li>Any reference type — accepts the literal value {@code "null"}
 * </ul>
 *
 * <p>Anything else is a compile error. Use {@link Compute} when the value isn't a literal (e.g. a
 * timestamp, a UUID, a value derived from program state).
 *
 * <p>The target field cannot also appear in {@link Bridge#renames()} or {@link Bridge#drops()} for
 * this pair — picking one of constant / compute / rename / drop per field is enforced at compile
 * time.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface Constant {
  /**
   * Target field name receiving the constant. Must name a real field on the {@link Bridge} target.
   */
  String field();

  /** The literal value as a string. Parsed at codegen time against the target field's type. */
  String value();
}
