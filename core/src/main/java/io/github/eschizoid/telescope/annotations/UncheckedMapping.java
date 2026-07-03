package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts the annotated element's {@code Telescope.map} / {@code mapper} / {@code mapperForward}
 * call sites from compile-time mapping verification. The construction-time validation still runs —
 * this only silences the compile-time twin, for sites whose rows or classes are assembled
 * dynamically and can't be statically analyzed anyway.
 *
 * <p>The {@code value} is a required, human-readable reason ("rows built from config", "classes
 * resolved at runtime") so every exemption documents itself at the site.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR })
public @interface UncheckedMapping {
  /** Why this site can't (or shouldn't) be statically verified. */
  String value();
}
