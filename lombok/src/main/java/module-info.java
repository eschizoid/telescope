/**
 * Lombok integration for the telescope code generator. Hosts {@link
 * com.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor}, which extends {@code
 * AbstractTelescopeProcessor} from {@code com.github.eschizoid.telescope.codegen} to emit {@code
 * <Bean>Focus} navigators for Lombok-shaped POJOs ({@code @Data} / {@code @Value} /
 * {@code @Builder}).
 *
 * <p>The processor is advertised via the {@link javax.annotation.processing.Processor} service —
 * {@code provides ... with ...} mirrors the {@code META-INF/services} entry so it is discoverable
 * on both the classpath and the module path.
 */
module com.github.eschizoid.telescope.lombok {
  requires transitive java.compiler;
  requires transitive com.github.eschizoid.telescope;
  requires transitive com.github.eschizoid.telescope.codegen;

  exports com.github.eschizoid.telescope.codegen.lombok;

  provides javax.annotation.processing.Processor
    with com.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor;
}
