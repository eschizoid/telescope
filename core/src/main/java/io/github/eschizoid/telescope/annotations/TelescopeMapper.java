package io.github.eschizoid.telescope.annotations;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates an injectable interface implementation, a Spring component or a CDI bean, that
 * structurally maps the source model to the target model. Declared transformer beans run in order
 * before the mapping. An interface extending {@link TelescopeProjection} supplies the source and
 * target types through its type arguments and can register additional transformers. Other
 * interfaces supply {@link #from()} and {@link #to()} explicitly; those plain interfaces require a
 * generated {@code @Bridge}.
 *
 * <p>The implementation is a Spring {@code @Component} when Spring is on the classpath of the
 * declaring module ({@code telescope-spring-boot-starter}), and an {@code @ApplicationScoped} CDI
 * bean when CDI is ({@code telescope-quarkus}); either way {@code telescope-codegen} goes on the
 * annotation processor path. With neither on the classpath the processor reports an error on the
 * annotated interface. With both it reports one too, until the compiler option {@code
 * -Atelescope.beans=spring} or {@code -Atelescope.beans=cdi} chooses. A CDI projection collects its
 * {@code TelescopeCustomizer} beans through Quarkus ArC's {@code @All}, so it needs ArC as well.
 * The annotation lives in core, next to {@link Bridge}, so each container integration generates its
 * own implementation from the same declaration.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TelescopeMapper {
  /**
   * Optional bean name: the Spring bean name, usable with {@code @Qualifier}, or the value of the
   * CDI bean's {@code @Named} qualifier.
   */
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
