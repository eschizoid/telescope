package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Marker for any row supplied to {@link Telescope#map(Class, Class, MapStep...)} / {@link
 * Telescope#mapper(Class, Class, MapStep...)} — either a {@link Mapping} (field correspondence) or
 * a {@link WriteHint} (per-target write-strategy override for the bean path).
 *
 * <p>Sealed to the two row kinds so the deep-mapping engine can partition a single varargs into
 * field overrides and construction hints without runtime cost. Users never name this type — they
 * static-import the factories on {@code Mapping} and {@code WriteHint} and the compiler upcasts
 * each row to {@code MapStep} at the call site.
 */
public sealed interface MapStep permits Mapping, WriteHint {}
