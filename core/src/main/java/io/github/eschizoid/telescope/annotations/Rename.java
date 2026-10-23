package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A single source-to-target field-name remapping for a {@link Bridge}. Used in the {@code renames}
 * array on {@code @Bridge} when the two sides of the bridge don't share the same field name:
 *
 * <pre>{@code
 * @Bridge(value = OrderEntity.class, renames = {
 *   @Rename(source = "orderNumber", target = "referenceCode"),
 *   @Rename(source = "totalCents",  target = "totalAmount")
 * })
 * public record Order(Long id, String orderNumber, long totalCents) {}
 * }</pre>
 *
 * <p>Both names must be real field names on their respective sides. A misspelled name on either
 * side is a compile error. Multiple renames cannot reference the same target name. A source field
 * left under its own name cannot have its same-named target taken by another field's rename: it
 * would have nowhere to be written, so a strict bridge refuses it by name, as the strict {@code
 * Telescope.mapper} does, and a lenient one leaves it unread, as {@code Telescope.mapperForward}
 * does. Drop it or rename it too. Such a field with a {@link Transform} or {@link ViaMapper} of its
 * own is refused either way, since that would be a second write into the same target. By default
 * two renames cannot share the same source either — set {@link #forwardOnly()} to {@code true} on
 * both (or all) of them to enable forward-only fan-out where one source field feeds multiple target
 * columns.
 *
 * <h2>Forward-only fan-out</h2>
 *
 * <p>Some enterprise audit shapes need a single source field to feed two or more correlated target
 * columns (the canonical {@code businessUnit → cretnUserId AND lastUpdtdUserId} case). Mark the
 * conflicting renames {@code forwardOnly = true} to opt in:
 *
 * <pre>{@code
 * @Bridge(value = AuditEntity.class, renames = {
 *   @Rename(source = "businessUnit", target = "cretnUserId",  forwardOnly = true),
 *   @Rename(source = "businessUnit", target = "lastUpdtdUserId", forwardOnly = true)
 * })
 * public record Audit(String businessUnit) {}
 * }</pre>
 *
 * <p>Forward writes the source value to <em>every</em> target column. Backward reconstructs the
 * source from the <em>first</em> declared fan-out target — declaration order is the contract. This
 * mirrors the runtime fan-out semantics enabled in {@code Telescope.mapper(...)}.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({})
public @interface Rename {
  /** The field name on the {@link Bridge} source. */
  String source();

  /** The field name on the {@link Bridge} target. */
  String target();

  /**
   * Opt in to forward-only fan-out when another {@link Rename} on the same {@link Bridge} declares
   * the same {@link #source()}. Backward direction reads from the first declared fan-out target
   * only — pick declaration order to control which target reconstructs the source. See the
   * type-level javadoc for the audit-column fan-out shape.
   */
  boolean forwardOnly() default false;
}
