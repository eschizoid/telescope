package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import java.util.Set;

/** Source side of {@link SameTypedContainerBenchmark}: one same-typed container component. */
@Bridge(SameTypedSetDto.class)
public record SameTypedSet(Set<Integer> values) {}
