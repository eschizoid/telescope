package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture paired with {@link HolderIsoSrc} for {@link
 * DeepMapHolderIsoReadTest}. Same-named scalar components so {@code Telescope.map(Source, Target)}
 * resolves without overrides — both sides' {@link
 * io.github.telescopeorganization.telescope.internal.MetadataHolderProbe MetadataHolderProbe} hits,
 * and the structural-iso holder path uses holder lens reads on both sides of the assembled {@code
 * Iso}.
 */
@Focus
public record HolderIsoDst(String name, int age) {}
