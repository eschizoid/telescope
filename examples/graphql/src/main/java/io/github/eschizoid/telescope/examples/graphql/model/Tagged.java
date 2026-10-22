package io.github.eschizoid.telescope.examples.graphql.model;

/**
 * A record whose one component is declared with its type variable. A runtime row typed by its type
 * argument, {@code Tagged<String>}, reads {@code value} as {@code String}, a class narrower than
 * the variable's erasure, so the mapper checks the value against {@code String} before its function
 * is handed it. Used by {@link io.github.eschizoid.telescope.examples.graphql.server.NativeVerify}
 * to exercise that check under native-image, where the mapper loads {@code String} by the name the
 * row's method reference records.
 *
 * @param value the tagged value
 * @param <X> the class of the tagged value
 */
public record Tagged<X>(X value) {}
