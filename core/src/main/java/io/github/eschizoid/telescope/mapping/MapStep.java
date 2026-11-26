package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Marker for any row supplied to {@link Telescope#map(Class, Class, MapStep...)} / {@link
 * Telescope#mapper(Class, Class, MapStep...)} — a {@link Mapping} (field correspondence), a {@link
 * WriteHint} (per-target bean write-strategy override), a {@link NullHint} (per-mapper
 * null-source-value strategy), a {@link Cases} (which case of a sealed target a case of a sealed
 * source converts to), or a {@link BackReference} (the property through which a collection's
 * elements point back at their owner).
 *
 * <p>Sealed to the five row kinds so the deep-mapping engine can partition a single varargs into
 * the relevant buckets without runtime cost. Users never name this type — they static-import the
 * factories on {@code Mapping}, {@code NullHint} and {@code Cases}, and the compiler upcasts each
 * row to {@code MapStep} at the call site.
 */
public sealed interface MapStep permits Mapping, WriteHint, NullHint, Cases, BackReference {}
