package io.github.eschizoid.telescope.benchmarks;

import java.util.List;

/**
 * The source {@link IntoCollectionBenchmark} writes into an {@link IntoListTgt}: a name and a list.
 */
public record IntoListSrc(String name, List<String> tags) {}
