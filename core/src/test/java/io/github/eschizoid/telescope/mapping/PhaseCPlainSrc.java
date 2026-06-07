package io.github.eschizoid.telescope.mapping;

/**
 * Top-level no-annotation fixture for {@link DeepMapPhaseCHolderTest}. Has no sibling {@code
 * <X>Telescope} holder, so the Phase C structural-iso path falls through to the reflective {@code
 * Records.read} read — the deep-mapping behaviour must be identical to the annotated-source case.
 */
public record PhaseCPlainSrc(String name, int age) {}
