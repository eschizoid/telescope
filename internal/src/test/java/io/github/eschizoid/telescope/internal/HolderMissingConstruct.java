package io.github.eschizoid.telescope.internal;

/**
 * Test fixture: target paired with a sibling that has {@code constants()} but no {@code
 * construct(Function)}.
 */
public record HolderMissingConstruct(String name) {}
