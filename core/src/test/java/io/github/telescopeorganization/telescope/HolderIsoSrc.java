package io.github.telescopeorganization.telescope;

import io.github.telescopeorganization.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture for {@link DeepMapHolderIsoReadTest}. The {@code FocusProcessor}
 * emits a sibling {@code HolderIsoSrcTelescope} metadata holder for this record; the change in
 * {@link io.github.telescopeorganization.telescope.internal.Reflective#structuralIso} routes the
 * instance-to-map read through the holder's pre-baked {@code Lens} constants.
 */
@Focus
public record HolderIsoSrc(String name, int age) {}
