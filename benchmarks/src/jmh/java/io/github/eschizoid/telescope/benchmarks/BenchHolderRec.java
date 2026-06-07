package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Annotated flat record fixture for the {@link HolderDispatchBenchmark} per-field rows. The
 * {@code @Focus} processor emits a sibling {@code BenchHolderRecTelescope} holder with public
 * static {@code Telescope<BenchHolderRec, ?>} constants for each component, so {@code
 * MetadataHolderProbe} short-circuits the LMF substrate at dispatch time.
 */
@Focus
public record BenchHolderRec(String id, String name, int age) {}
