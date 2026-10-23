/**
 * Telescope JMH benchmarks. Declared as its own module so the JPMS metadata is complete across the
 * project; consumed only by the JMH harness, which runs the benchmark classes by reflection.
 *
 * <p>Requires {@code io.github.eschizoid.telescope} for the DSL surface and the {@code @Focus} /
 * {@code @Bridge} annotations; the {@code :codegen} processor runs on the annotation-processor path
 * (not the module path) and emits its generated {@code <X>Path<R>} navigator / {@code <X>Bridge}
 * classes back into this module's {@code io.github.eschizoid.telescope.benchmarks} package, which
 * is why the benchmarks live in a dedicated sub-package rather than splitting the core module's
 * exported {@code io.github.eschizoid.telescope} package.
 */
module io.github.eschizoid.telescope.benchmarks {
  requires transitive io.github.eschizoid.telescope;
  requires io.github.eschizoid.telescope.spring;
  requires jmh.core;

  exports io.github.eschizoid.telescope.benchmarks;

  provides io.github.eschizoid.telescope.conversion.BridgeProvider
    with
      io.github.eschizoid.telescope.benchmarks.McFieldFlatBeanToMcFlatRecBridgeProvider,
      io.github.eschizoid.telescope.benchmarks.McFieldNestedBeanToMcNestedRecBridgeProvider,
      io.github.eschizoid.telescope.benchmarks.ShField1ToShDst1BridgeProvider,
      io.github.eschizoid.telescope.benchmarks.ShField2ToShDst2BridgeProvider,
      io.github.eschizoid.telescope.benchmarks.ShField3ToShDst3BridgeProvider,
      io.github.eschizoid.telescope.benchmarks.ShField4ToShDst4BridgeProvider,
      io.github.eschizoid.telescope.benchmarks.VbFieldSrcToVbTgtBridgeProvider,
      io.github.eschizoid.telescope.benchmarks.VbClassSrcToVbTgtBridgeProvider;
}
