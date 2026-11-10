package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;
import java.util.List;

/** Shared-prefix container for the navigator-path tree rows of {@code MultiEditBenchmark}. */
@Focus
public record BenchFusionOrg(String title, List<BenchFusionUser> users) {}
