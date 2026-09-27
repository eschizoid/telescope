package io.github.eschizoid.telescope.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable {@link TelescopePath} for a record field path. The path is assembled once
 * when the bean is created; reads and updates reuse the resulting Telescope value.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeTransform {
  /** Root record type. */
  Class<?> from();

  /** Value type at the end of {@link #path()}. */
  Class<?> to();

  /** Dot-separated record component names, for example {@code "customer.email"}. */
  String path();
}
