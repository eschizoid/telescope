package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Marker for any row supplied to {@link Telescope#map(Class, Class, MapStep...)} / {@link
 * Telescope#mapper(Class, Class, MapStep...)} — a {@link Mapping} (field correspondence), a {@link
 * WriteHint} (per-target bean write-strategy override), or a {@link NullHint} (per-mapper
 * null-source-value strategy).
 *
 * <p>Sealed to the three row kinds so the deep-mapping engine can partition a single varargs into
 * the relevant buckets without runtime cost. Users never name this type — they static-import the
 * factories on {@code Mapping}, {@code WriteHint}, {@code NullHint} and the compiler upcasts each
 * row to {@code MapStep} at the call site.
 */
public sealed interface MapStep permits Mapping, WriteHint, NullHint {}
