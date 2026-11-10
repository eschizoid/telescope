package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Map;

/** Source side of {@link SameTypedContainerBenchmark}: one same-typed container component. */
@Bridge(SameTypedMapDto.class)
public record SameTypedMap(Map<Integer, Integer> values) {}
