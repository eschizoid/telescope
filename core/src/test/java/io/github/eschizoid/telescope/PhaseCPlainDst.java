package io.github.eschizoid.telescope;

/**
 * Top-level no-annotation fixture paired with {@link PhaseCPlainSrc} for {@link
 * DeepMapPhaseCHolderTest}. Verifies that the Phase C structural-iso change preserves the prior
 * behavior when neither side carries a sibling {@code <X>Telescope} holder.
 */
public record PhaseCPlainDst(String name, int age) {}
