package io.github.eschizoid.telescope.conversion;

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
}
