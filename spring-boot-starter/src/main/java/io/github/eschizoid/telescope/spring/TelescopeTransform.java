package io.github.eschizoid.telescope.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable {@link TelescopeTransformation} from default {@code path()} and {@code
 * transform()} methods. The path and transformation are cached once when the bean is created. Root
 * and focus types come from {@code TelescopeTransformation<S, A>}, including inherited generic
 * arguments. Both factories must return non-null values and must not depend on field injection. The
 * annotated interface name must end with {@code Transformer}.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeTransform {
  /** Optional Spring bean name, usable with {@code @Qualifier}. */
  String value() default "";
}
