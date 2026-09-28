package io.github.eschizoid.telescope.annotations;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable Spring interface implementation that structurally maps the source model
 * to the target model. Declared transformer beans run in order before the mapping. An interface
 * extending {@link TelescopeProjection} supplies the source and target types through its type
 * arguments and can register additional transformers. Other interfaces supply {@link #from()} and
 * {@link #to()} explicitly; those plain interfaces require a generated {@code @Bridge}.
 *
 * <p>The implementation is a Spring component, so this annotation needs {@code
 * telescope-spring-boot-starter} on the classpath and {@code telescope-codegen} on the annotation
 * processor path. Without Spring the processor reports an error on the annotated interface. The
 * annotation lives in core, next to {@link Bridge}, so another container integration can generate
 * its own implementation from the same declaration.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeMapper {
  /** Optional Spring bean name, usable with {@code @Qualifier}. */
  String value() default "";

  /** Source model type; inferred from {@link TelescopeProjection} when omitted. */
  Class<?> from() default Void.class;

  /** Destination model type; inferred from {@link TelescopeProjection} when omitted. */
  Class<?> to() default Void.class;

  /**
   * Transformer beans injected in declaration order and applied before the bridge. Each transformer
   * must focus on the declared source model type.
   */
  Class<? extends TelescopeTransformation<?, ?>>[] transformers() default {};
}
