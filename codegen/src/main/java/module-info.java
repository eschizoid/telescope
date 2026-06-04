/**
 * Telescope code generator — annotation processors that emit {@code <Type>Focus} navigators and
 * {@code <Record>Bridge} classes at compile time.
 *
 * <p>The {@code com.github.eschizoid.telescope.codegen} package is exported so downstream
 * processors (e.g. {@code telescope-lombok}) can extend {@link
 * com.github.eschizoid.telescope.codegen.AbstractTelescopeProcessor}. The processors themselves are
 * advertised via the {@link javax.annotation.processing.Processor} service — {@code provides ...
 * with ...} below mirrors the {@code META-INF/services} entries so the processor is discoverable on
 * the module path as well as the classpath.
 */
module com.github.eschizoid.telescope.codegen {
  requires transitive java.compiler;
  requires transitive com.github.eschizoid.telescope;

  exports com.github.eschizoid.telescope.codegen;

  provides javax.annotation.processing.Processor
    with
      com.github.eschizoid.telescope.codegen.FocusProcessor,
      com.github.eschizoid.telescope.codegen.BeanFocusProcessor,
      com.github.eschizoid.telescope.codegen.BridgeProcessor;
}
