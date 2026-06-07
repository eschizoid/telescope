package io.github.eschizoid.telescope.mapping;

/**
 * Test fixture for {@link DeepMapPhaseDConstructTest}: a record paired with a hand-written sibling
 * {@code PhaseDLegacyHolderTargetTelescope} that simulates a pre-Phase-D holder shape — Phase A
 * lens constants only, no {@code construct(Function<String, Object>)} method. The runtime probe
 * must surface a {@code null} {@link
 * io.github.eschizoid.telescope.internal.MetadataHolderProbe.HolderRef#constructor()
 * HolderRef.constructor()} so {@link
 * io.github.eschizoid.telescope.internal.Reflective#structuralIso Reflective.structuralIso} falls
 * back to the reflective canonical-ctor path. Without {@code @Focus} the codegen wouldn't fire on
 * this record, which lets the hand-written holder stand without conflict.
 */
public record PhaseDLegacyHolderTarget(String name, int age) {}
