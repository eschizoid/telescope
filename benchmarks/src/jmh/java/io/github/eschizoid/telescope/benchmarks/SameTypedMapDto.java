package io.github.eschizoid.telescope.benchmarks;

import java.util.Map;

/** Target side of {@link SameTypedContainerBenchmark}. */
public record SameTypedMapDto(Map<Integer, Integer> values) {}
