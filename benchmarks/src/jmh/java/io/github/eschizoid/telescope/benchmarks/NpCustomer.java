package io.github.eschizoid.telescope.benchmarks;

/**
 * The object {@link NestedPathBenchmark}'s path steps through first.
 *
 * @param address the object the path steps through next
 * @param name not on the path
 */
public record NpCustomer(NpAddress address, String name) {}
