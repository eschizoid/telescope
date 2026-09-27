package io.github.eschizoid.telescope.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable {@link TelescopeTransformation} from default {@code path()} and {@code
 * transform()} methods. The path and transformation are cached once when the bean is created. Root
 * and focus types come from {@code TelescopeTransformation<S, A>}, including inherited generic
 * arguments. Both factories must return non-null values and must not depend on field injection. By
 * convention the interface name ends with {@code Transformer}; the processor does not require it.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeTransformer {
  /** Optional Spring bean name, usable with {@code @Qualifier}. */
  String value() default "";
}
