package io.github.eschizoid.telescope.internal;

/**
 * Top-level no-annotation fixture for {@link HybridDispatchIntegrationTest}. Has no sibling {@code
 * <X>Telescope} holder, so {@code Telescope.of(...).field(...)} routes through the existing {@code
 * Records.fieldLens} reflective path — Phase B's probe must transparently fall through when no
 * holder is on the classpath.
 */
public record HybridDispatchPlainUser(String name, int age) {}
