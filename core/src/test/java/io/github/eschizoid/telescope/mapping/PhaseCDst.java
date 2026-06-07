package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture paired with {@link PhaseCSrc} for {@link
 * DeepMapPhaseCHolderTest}. Same-named scalar components so {@link
 * io.github.eschizoid.telescope.Telescope#map(Class, Class, MapStep...) Telescope.map(Source,
 * Target)} resolves without overrides — both sides' {@link
 * io.github.eschizoid.telescope.internal.MetadataHolderProbe MetadataHolderProbe} hits, and the
 * Phase C structural-iso path uses holder lens reads on both sides of the assembled {@code Iso}.
 */
@Focus
public record PhaseCDst(String name, int age) {}
