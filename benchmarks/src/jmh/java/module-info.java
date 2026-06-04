/**
 * Telescope JMH benchmarks. Declared as its own module so the JPMS metadata is complete across the
 * project; consumed only by the JMH harness, which runs the benchmark classes by reflection.
 *
 * <p>Requires {@code com.github.eschizoid.telescope} for the DSL surface and the {@code @Focus} /
 * {@code @Bridge} annotations; the {@code :codegen} processor runs on the annotation-processor path
 * (not the module path) and emits its generated {@code *Focus} / {@code *Bridge} classes back into
 * this module's {@code com.github.eschizoid.telescope.benchmarks} package, which is why the
 * benchmarks live in a dedicated sub-package rather than splitting the core module's exported
 * {@code com.github.eschizoid.telescope} package.
 */
module com.github.eschizoid.telescope.benchmarks {
  requires transitive com.github.eschizoid.telescope;
  requires jmh.core;

  exports com.github.eschizoid.telescope.benchmarks;
}
