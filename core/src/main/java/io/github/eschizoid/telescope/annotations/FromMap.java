package io.github.eschizoid.telescope.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record or JavaBeans-style POJO for reflection-free {@code Map<String, Object>} ingestion.
 * The {@code telescope-codegen} processor emits a sibling {@code <X>FromMap} class with a {@code
 * public static X fromMap(Map<String, Object>)} method and a {@code public static final
 * ForwardMapper<Map<String, Object>, X> FROM_MAP} constant — a direct canonical-constructor (or
 * builder/setter) rebuild with the map values coerced inline.
 *
 * <p>This is the generated-code sibling of the runtime {@link
 * io.github.eschizoid.telescope.Telescope#fromMap(Class,
 * io.github.eschizoid.telescope.mapping.MapExtractStep...)}. Where the runtime form recovers field
 * names from method references via {@code SerializedLambda} (and so can't run in a GraalVM native
 * image), the generated form embeds the field names at compile time and uses no reflection — it is
 * native-image clean by construction.
 *
 * <p>The map key for each field is the field name, and the value is coerced to the field's type by
 * the standard rules: {@code String} as-is, {@code String}/{@code Number} to a primitive, a {@code
 * String} enum name to the enum, a nested {@code Map} to a nested {@code @FromMap} type, and {@code
 * List}/{@code Set}/{@code Map} element-mapped. An absent key takes the field's JLS default.
 *
 * <pre>{@code
 * @FromMap
 * record User(String name, int age, Role role, Address address) {}
 *
 * // Generated alongside:
 * //   public final class UserFromMap {
 * //     public static User fromMap(Map<String, Object> map) { ... }
 * //     public static final ForwardMapper<Map<String, Object>, User> FROM_MAP = ...;
 * //   }
 *
 * User u = UserFromMap.fromMap(graphqlInput);   // no reflection
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface FromMap {}
