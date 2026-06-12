package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation that lets {@link Bridge} appear more than once on the same type. Generated
 * implicitly by {@code javac} when a user writes two or more {@code @Bridge(...)} annotations on a
 * single source; usually you don't write {@code @Bridges} directly.
 *
 * <pre>{@code
 * // The user writes this:
 * @Bridge(ProductEntity.class)
 * @Bridge(ProductDto.class)
 * public record Product(...) {}
 *
 * // javac wraps it as:
 * @Bridges({@Bridge(ProductEntity.class), @Bridge(ProductDto.class)})
 * public record Product(...) {}
 * }</pre>
 *
 * <p>{@code BridgeProcessor} reads either form. Each contained {@code @Bridge} emits its own
 * sibling {@code <Source>To<Target>Bridge} class with a {@code BRIDGE} constant; when a source has
 * multiple targets, the long {@code <Source>To<Target>Bridge} naming is used uniformly so the
 * emitted class names stay unambiguous.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Bridges {
  Bridge[] value();
}
