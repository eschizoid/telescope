package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

/** Source side of {@link SameTypedContainerBenchmark}: one same-typed container component. */
@Bridge(SameTypedListDto.class)
public record SameTypedList(List<Integer> values) {}
