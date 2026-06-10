package io.github.eschizoid.telescope.internal;

/**
 * Top-level no-annotation fixture for {@link MetadataHolderProbeTest}. Has no sibling {@code
 * <X>Telescope} class on the classpath, so {@link MetadataHolderProbe#probeFor} should return
 * {@link java.util.Optional#empty()} and the dispatch sites should fall through to the reflective
 * {@code Records.fieldLens} path.
 */
public record UnannotatedRecord(String name) {}
