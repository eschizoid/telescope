/**
 * Lombok integration for the telescope code generator. Hosts {@link
 * io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor}, which extends {@code
 * AbstractTelescopeProcessor} from {@code io.github.eschizoid.telescope.codegen} to emit {@code
 * <Pojo>Path<R>} navigators for Lombok-shaped POJOs ({@code @Data} / {@code @Value} /
 * {@code @Builder}).
 *
 * <p>The processor is advertised via the {@link javax.annotation.processing.Processor} service —
 * {@code provides ... with ...} mirrors the {@code META-INF/services} entry so it is discoverable
 * on both the classpath and the module path.
 */
module io.github.eschizoid.telescope.lombok {
  requires transitive java.compiler;
  requires transitive io.github.eschizoid.telescope;
  requires transitive io.github.eschizoid.telescope.codegen;

  exports io.github.eschizoid.telescope.codegen.lombok;

  provides javax.annotation.processing.Processor with io.github.eschizoid.telescope.codegen.lombok.LombokFocusProcessor;
}
