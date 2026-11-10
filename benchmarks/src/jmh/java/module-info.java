/**
 * Telescope JMH benchmarks. Declared as its own module so the JPMS metadata is complete across the
 * project; consumed only by the JMH harness, which runs the benchmark classes by reflection.
 *
 * <p>Requires {@code io.github.telescopeorganization.telescope} for the DSL surface and the
 * {@code @Focus} / {@code @Bridge} annotations; the {@code :codegen} processor runs on the
 * annotation-processor path (not the module path) and emits its generated {@code <X>Path<R>}
 * navigator / {@code <X>Bridge} classes back into this module's {@code
 * io.github.telescopeorganization.telescope.benchmarks} package, which is why the benchmarks live
 * in a dedicated sub-package rather than splitting the core module's exported {@code
 * io.github.telescopeorganization.telescope} package.
 */
module io.github.telescopeorganization.telescope.benchmarks {
  requires transitive io.github.telescopeorganization.telescope;
  requires io.github.telescopeorganization.telescope.spring;
  requires jmh.core;

  exports io.github.telescopeorganization.telescope.benchmarks;

  provides io.github.telescopeorganization.telescope.conversion.BridgeProvider
    with
      io.github.telescopeorganization.telescope.benchmarks.McFieldFlatBeanToMcFlatRecBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.McFieldNestedBeanToMcNestedRecBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.ShField1ToShDst1BridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.ShField2ToShDst2BridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.ShField3ToShDst3BridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.ShField4ToShDst4BridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.VbFieldSrcToVbTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.VbClassSrcToVbTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.VbSrcToVbTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.CycleSeatToCycleSeatDtoBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.BenchWrapSrcToBenchWrapTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.BenchTxWideSrcToBenchTxTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.BenchTxSrcToBenchTxTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.FillSrcToFillTgtBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.IntoFieldSrcToIntoNarrowBridgeProvider,
      io.github.telescopeorganization.telescope.benchmarks.IntoFieldWideSrcToIntoWideBridgeProvider;
}
