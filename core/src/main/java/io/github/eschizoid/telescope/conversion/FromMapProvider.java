package io.github.eschizoid.telescope.conversion;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Service-provider interface through which a binder generated for a {@code @FromMap} type announces
 * itself. The {@code @FromMap} processor emits one implementation per binder, nested in it as
 * {@code <Name>FromMap.Provider}, and lists it in {@code META-INF/services}.
 *
 * <p>The annotation itself is source-retained, so a registered provider is how the runtime {@code
 * Telescope.fromMap} knows a type has a generated binder. The processor asks the same question of a
 * type compiled earlier by looking for the same nested provider on its binder, so both paths accept
 * the same component types.
 *
 * <p>Where {@link ServiceLoader} finds the provider depends on how the binder's code is loaded. On
 * the class path, and in a native image, the {@code META-INF/services} entry is enough. A named
 * module ignores that file and is searched only through its {@code provides} directives, so its
 * {@code module-info} has to declare {@code provides
 * io.github.eschizoid.telescope.conversion.FromMapProvider with <pkg>.<Name>FromMap.Provider}; the
 * processor reports the line when the module it compiles lacks it. A jar that merges other jars
 * must merge their service files too, or the entries of all but one are lost. Implementations must
 * expose a public no-argument constructor.
 */
public interface FromMapProvider {
  /** The {@code @FromMap} type the binder builds. */
  Class<?> targetType();

  /**
   * The generated binder itself, which a runtime {@code fromMap} calls for a component of {@link
   * #targetType()} that no row names. A nested type is built through its own binder rather than
   * rebuilt by the runtime, because only the binder knows what the source-retained annotation
   * declared, such as its required keys.
   *
   * <p>A provider that does not override this method cannot build its type, so a runtime {@code
   * fromMap} that needs it refuses while the mapper is built.
   *
   * @throws UnsupportedOperationException when the provider does not override it
   */
  default ForwardMapper<Map<String, Object>, ?> binder() {
    throw new UnsupportedOperationException(
      "a FromMapProvider that does not override binder() cannot build " +
        targetType().getName() +
        "; recompile " +
        targetType().getSimpleName() +
        " with the current telescope-codegen"
    );
  }

  /**
   * The keys {@code @FromMap(required = ...)} declares on {@link #targetType()}, each the name of
   * the component or property it fills. The annotation is source-retained, so this is how a runtime
   * {@code Telescope.fromMap} over the same type refuses the maps the binder refuses.
   *
   * <p>A provider that does not override this method cannot say which keys are required, so a
   * runtime {@code fromMap} over its type refuses while the mapper is built.
   *
   * @throws UnsupportedOperationException when the provider does not override it
   */
  default List<String> required() {
    throw new UnsupportedOperationException(
      "a FromMapProvider that does not override required() cannot say which keys " +
        targetType().getName() +
        " requires; recompile " +
        targetType().getSimpleName() +
        " with the current telescope-codegen"
    );
  }
}
