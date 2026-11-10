/**
 * Telescope code generator — annotation processors that emit {@code <Type>Path<R>} navigators and
 * {@code <Source>Bridge} classes at compile time.
 *
 * <p>The {@code io.github.telescopeorganization.telescope.codegen} package is exported so
 * downstream processors (e.g. {@code telescope-lombok}) can extend {@link
 * io.github.telescopeorganization.telescope.codegen.AbstractTelescopeProcessor}. The processors
 * themselves are advertised via the {@link javax.annotation.processing.Processor} service — {@code
 * provides ... with ...} below mirrors the {@code META-INF/services} entries so the processor is
 * discoverable on the module path as well as the classpath.
 */
module io.github.telescopeorganization.telescope.codegen {
  requires transitive java.compiler;
  requires transitive io.github.telescopeorganization.telescope;
  // The shared pairing decision spec (qualified-exported to this module) plus the javac tree API
  // power MapperVerifierProcessor's compile-time replay of construction-time pairing decisions.
  requires io.github.telescopeorganization.telescope.internal;
  requires jdk.compiler;

  exports io.github.telescopeorganization.telescope.codegen;

  provides javax.annotation.processing.Processor
    with
      io.github.telescopeorganization.telescope.codegen.FocusProcessor,
      io.github.telescopeorganization.telescope.codegen.BeanFocusProcessor,
      io.github.telescopeorganization.telescope.codegen.BridgeProcessor,
      io.github.telescopeorganization.telescope.codegen.FromMapProcessor,
      io.github.telescopeorganization.telescope.codegen.TelescopeMapperProcessor,
      io.github.telescopeorganization.telescope.codegen.MapperVerifierProcessor;
}
