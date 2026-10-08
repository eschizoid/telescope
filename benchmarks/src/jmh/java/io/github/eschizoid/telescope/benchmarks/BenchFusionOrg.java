package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;

/** Shared-prefix container for the navigator-path tree rows of {@code MultiEditBenchmark}. */
@Focus
public record BenchFusionOrg(String title, List<BenchFusionUser> users) {}
