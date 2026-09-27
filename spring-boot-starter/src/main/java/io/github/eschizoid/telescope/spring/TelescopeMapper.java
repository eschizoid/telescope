package io.github.eschizoid.telescope.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable Spring interface implementation backed by the source model's existing
 * {@code @Bridge(to)}. Field pairing, defaults, and conversions belong to that bridge.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeMapper {
  /** Source model type, which must have a matching {@code @Bridge}. */
  Class<?> from();

  /** Destination model type. */
  Class<?> to();
}
