package io.github.eschizoid.telescope.benchmarks;

/**
 * Flat-tier record fixture for {@link MapStructComparisonBenchmark}. Same five scalar fields as
 * {@link McFlatBean}, same names, same types — the same-name-bijection that both MapStruct's
 * auto-resolution and telescope's deep-mapping factory expect.
 */
public record McFlatRec(Long id, String email, String name, int age, boolean active) {}
