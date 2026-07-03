/**
 * Telescope code generator — annotation processors that emit {@code <Type>Path<R>} navigators and
 * {@code <Source>Bridge} classes at compile time.
 *
 * <p>The {@code io.github.eschizoid.telescope.codegen} package is exported so downstream processors
 * (e.g. {@code telescope-lombok}) can extend {@link
 * io.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor}. The processors themselves are
 * advertised via the {@link javax.annotation.processing.Processor} service — {@code provides ...
 * with ...} below mirrors the {@code META-INF/services} entries so the processor is discoverable on
 * the module path as well as the classpath.
 */
module io.github.eschizoid.telescope.codegen {
  requires transitive java.compiler;
  requires transitive io.github.eschizoid.telescope;
  // The shared pairing decision spec (qualified-exported to this module) plus the javac tree API
  // power MapperVerifierProcessor's compile-time replay of construction-time pairing decisions.
  requires io.github.eschizoid.telescope.internal;
  requires jdk.compiler;

  exports io.github.eschizoid.telescope.codegen;

  provides javax.annotation.processing.Processor
    with
      io.github.eschizoid.telescope.codegen.FocusProcessor,
      io.github.eschizoid.telescope.codegen.BeanFocusProcessor,
      io.github.eschizoid.telescope.codegen.BridgeProcessor,
      io.github.eschizoid.telescope.codegen.FromMapProcessor,
      io.github.eschizoid.telescope.codegen.MapperVerifierProcessor;
}
