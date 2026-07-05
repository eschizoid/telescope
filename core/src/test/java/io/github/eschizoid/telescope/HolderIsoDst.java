package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture paired with {@link HolderIsoSrc} for {@link
 * DeepMapHolderIsoReadTest}. Same-named scalar components so {@code Telescope.map(Source, Target)}
 * resolves without overrides — both sides' {@link
 * io.github.eschizoid.telescope.internal.MetadataHolderProbe MetadataHolderProbe} hits, and the
 * structural-iso holder path uses holder lens reads on both sides of the assembled {@code Iso}.
 */
@Focus
public record HolderIsoDst(String name, int age) {}
