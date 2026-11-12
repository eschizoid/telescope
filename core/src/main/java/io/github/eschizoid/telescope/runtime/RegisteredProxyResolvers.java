package io.github.eschizoid.telescope.runtime;

import io.github.eschizoid.telescope.conversion.ProxyResolver;
import io.github.eschizoid.telescope.internal.ProxyClasses;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * The proxy resolution the substrate keys its caches on: every {@link ProxyResolver} registered
 * through {@link ServiceLoader} and active in this runtime, asked in the order the loader finds
 * them. Registered with the substrate as its one {@link ProxyClasses}; inactive where no resolver
 * is, so a read then costs no call.
 *
 * <p>Each registration is loaded on its own. One that cannot be loaded, whose constructor throws or
 * whose {@code active} throws, is left out and reported at {@code WARNING} through a {@link
 * Logger}, naming it, and every other resolver is still loaded. A resolver whose {@code classOf}
 * throws gives no answer for that value, and the next one is asked.
 *
 * <p>Public only because {@link ServiceLoader} instantiates it; not part of the API.
 */
public final class RegisteredProxyResolvers implements ProxyClasses {

  private static final Logger LOGGER = System.getLogger(RegisteredProxyResolvers.class.getName());

  /** More registrations than any class path holds for this service. */
  private static final int MAX_REGISTRATIONS = 64;

  private final ProxyResolver[] resolvers;

  /** Loads every active {@link ProxyResolver}, reporting one that cannot be loaded. */
  public RegisteredProxyResolvers() {
    this(ServiceLoader.load(ProxyResolver.class, ProxyResolver.class.getClassLoader()).iterator(), refused ->
      LOGGER.log(Level.WARNING, refused)
    );
  }

  RegisteredProxyResolvers(final Iterator<ProxyResolver> registered, final Consumer<String> refused) {
    final List<ProxyResolver> active = new ArrayList<>();
    // Bounded, so a loader that failed on the same registration again could not spin forever.
    for (var attempts = 0; attempts < MAX_REGISTRATIONS; attempts++) {
      final ProxyResolver resolver;
      try {
        if (!registered.hasNext()) {
          break;
        }
        resolver = registered.next();
      } catch (final ServiceConfigurationError unloadable) {
        refused.accept("telescope: a ProxyResolver could not be loaded and is left out: " + unloadable.getMessage());
        continue;
      }
      try {
        if (resolver.active()) {
          active.add(resolver);
        }
      } catch (final RuntimeException failed) {
        refused.accept(
          "telescope: ProxyResolver " +
            resolver.getClass().getName() +
            " failed to say whether it is active and is" +
            " left out: " +
            failed
        );
      }
    }
    this.resolvers = active.toArray(ProxyResolver[]::new);
  }

  @Override
  public Class<?> resolve(final Object value) {
    for (final var resolver : resolvers) {
      final Class<?> resolved;
      try {
        resolved = resolver.classOf(value);
      } catch (final RuntimeException noAnswer) {
        // A resolver that cannot tell gives no answer, as one that does not know the value does.
        continue;
      }
      if (resolved != null) {
        return resolved;
      }
    }
    return null;
  }

  @Override
  public boolean active() {
    return resolvers.length > 0;
  }
}
