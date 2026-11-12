package io.github.eschizoid.telescope.annotations;

import io.github.eschizoid.telescope.inject.TelescopeTransformation;
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
 *
 * <p>Like {@link TelescopeMapper}, the generated implementation is a Spring component or an {@code
 * ApplicationScoped} CDI bean, as the classpath of the declaring module or the compiler option
 * {@code -Atelescope.beans} decides, and needs {@code telescope-spring-boot-starter} or {@code
 * telescope-quarkus} on the classpath.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeTransformer {
  /**
   * Optional bean name: the Spring bean name, usable with {@code @Qualifier}, or the value of the
   * CDI bean's {@code @Named} qualifier.
   */
  String value() default "";
}
