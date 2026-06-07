package io.github.eschizoid.telescope.benchmarks;

/**
 * Unannotated structural twin of {@link BenchHolderRec} for the {@link HolderDispatchBenchmark}
 * per-field rows. No {@code @Focus}, so no sibling holder is emitted and {@code
 * MetadataHolderProbe} returns {@code Optional.empty()} at the dispatch site — the LMF substrate
 * runs the field-lens build.
 */
public record BenchPlainRec(String id, String name, int age) {}
