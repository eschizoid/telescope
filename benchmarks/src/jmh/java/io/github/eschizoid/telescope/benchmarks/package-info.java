/**
 * JMH micro-benchmarks for the telescope DSL. Lives in its own sub-package so it can declare a
 * dedicated {@code module-info.java} without colliding with the {@code
 * io.github.eschizoid.telescope} package already exported by the core module (JPMS forbids split
 * packages).
 *
 * <ul>
 *   <li>{@link io.github.eschizoid.telescope.benchmarks.TelescopeBenchmark} — measures the three
 *       update paths against each other: reflective {@code .field(...)}, the reflection-free {@code
 *       Telescope.lens(getter, setter)} constant, and the generated {@code *Focus} / {@code
 *       *Bridge} constants emitted by the {@code :codegen} processor.
 *   <li>{@link io.github.eschizoid.telescope.benchmarks.BenchUserA} / {@link
 *       io.github.eschizoid.telescope.benchmarks.BenchUserB} — top-level POJO fixtures used by the
 *       generated-{@code @Bridge} benchmark (must be top-level because {@code @Bridge} generates a
 *       sibling class).
 * </ul>
 */
package io.github.eschizoid.telescope.benchmarks;
