package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
 * side is a compile error. Multiple renames may target the same direction (one record field at a
 * time); no two renames can reference the same source name or the same target name.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface Rename {
  /** The field name on the {@link Bridge} source. */
  String source();

  /** The field name on the {@link Bridge} target. */
  String target();
}
