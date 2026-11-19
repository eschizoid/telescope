package io.github.eschizoid.telescope.jackson;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a reflection-free JSON binder for a top-level record or bean: a sibling {@code
 * <Name>FromJson} whose {@code BINDER} is a {@link JsonBinder} reading each object through a parser
 * loop written at compile time. It reads by the rules {@link TelescopeJson#fromJson} reads by,
 * through the same code, and needs no reflection registration in a native image.
 *
 * <pre>{@code
 * @FromJson
 * public record Order(String number, List<Line> lines) {}
 *
 * Order order = OrderFromJson.BINDER.read(json);
 * }</pre>
 *
 * <p>The processor ships in telescope-jackson and runs where the module is on the annotation
 * processor path. A type {@code TelescopeJson.fromJson} refuses is refused at compile time in the
 * same words; a bean built otherwise than through a no-argument constructor and setters, a setter
 * taking another type than its getter returns, a generic type and a type whose {@code @FromMap}
 * binder was compiled apart from it are refused by name, since the processor cannot read them as
 * the runtime does.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface FromJson {}
