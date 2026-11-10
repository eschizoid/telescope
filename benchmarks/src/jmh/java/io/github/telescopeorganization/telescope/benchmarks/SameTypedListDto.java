package io.github.telescopeorganization.telescope.benchmarks;

import java.util.List;

/** Target side of {@link SameTypedContainerBenchmark}. */
public record SameTypedListDto(List<Integer> values) {}
