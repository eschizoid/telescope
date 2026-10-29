package io.github.eschizoid.telescope.benchmarks;

/**
 * The target of {@link FillPlainSrc}.
 *
 * @param id read from the source
 * @param region read from the source
 */
public record FillPlainTgt(String id, String region) {}
