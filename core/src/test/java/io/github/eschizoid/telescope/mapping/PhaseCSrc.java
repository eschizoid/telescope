package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Top-level {@code @Focus} fixture for {@link DeepMapPhaseCHolderTest}. The {@code FocusProcessor}
 * emits a sibling {@code PhaseCSrcTelescope} metadata holder for this record (ADR-0006 Phase A);
 * the Phase C change in {@link io.github.eschizoid.telescope.internal.Reflective#structuralIso}
 * routes the instance-to-map read through the holder's pre-baked {@code Lens} constants.
 */
@Focus
public record PhaseCSrc(String name, int age) {}
