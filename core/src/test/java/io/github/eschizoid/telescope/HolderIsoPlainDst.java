package io.github.eschizoid.telescope;

/**
 * Top-level no-annotation fixture paired with {@link HolderIsoPlainSrc} for {@link
 * DeepMapHolderIsoReadTest}. Verifies that the structural-iso holder path preserves the prior
 * behavior when neither side carries a sibling {@code <X>Telescope} holder.
 */
public record HolderIsoPlainDst(String name, int age) {}
