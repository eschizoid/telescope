package io.github.eschizoid.telescope.internal;

/**
 * Test fixture: target class whose sibling {@link HolderMissingConstantsFieldOptics} deliberately
 * omits the required {@code constants()} method. Pins the {@link MetadataHolderProbe} shape-check
 * diagnostic — a future codegen out-of-sync with the runtime probe would surface here as a precise
 * {@code IllegalStateException} naming the missing method, instead of a cryptic {@code
 * NoSuchMethodException} deep in the dispatch site.
 */
public record HolderMissingConstants(String name) {}
