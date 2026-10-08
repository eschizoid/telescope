package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Set;

/** Source side of {@link SameTypedContainerBenchmark}: one same-typed container component. */
@Bridge(SameTypedSetDto.class)
public record SameTypedSet(Set<Integer> values) {}
