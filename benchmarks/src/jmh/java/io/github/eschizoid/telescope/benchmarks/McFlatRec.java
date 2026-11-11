package io.github.eschizoid.telescope.benchmarks;

/**
 * Flat-tier record fixture for {@link MapStructComparisonBenchmark}. Same five scalar fields as
 * {@link McFlatBean}, same names, same types — the same-name-bijection that both MapStruct's
 * auto-resolution and telescope's deep-mapping factory expect. It implements the {@code
 * McFlatRecMappings} interface the {@link McFieldMappers#FLAT} field generates, for the {@code
 * _field_call_backward} row.
 */
public record McFlatRec(Long id, String email, String name, int age, boolean active) implements McFlatRecMappings {}
