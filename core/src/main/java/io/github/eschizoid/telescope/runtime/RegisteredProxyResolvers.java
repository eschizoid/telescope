package io.github.eschizoid.telescope.runtime;

import io.github.eschizoid.telescope.conversion.ProxyResolver;
import io.github.eschizoid.telescope.internal.ProxyClasses;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The proxy resolution the substrate keys its caches on: every {@link ProxyResolver} registered
 * through {@link ServiceLoader} and active in this runtime, asked in the order the loader finds
 * them. Registered with the substrate as its one {@link ProxyClasses}; inactive where no resolver
 * is, so a read then costs no call.
 *
 * <p>Public only because {@link ServiceLoader} instantiates it; not part of the API.
 */
public final class RegisteredProxyResolvers implements ProxyClasses {

  private final ProxyResolver[] resolvers;

  /** Loads every active {@link ProxyResolver}. */
  public RegisteredProxyResolvers() {
    this(ServiceLoader.load(ProxyResolver.class, ProxyResolver.class.getClassLoader()));
  }

  RegisteredProxyResolvers(final Iterable<ProxyResolver> registered) {
    final List<ProxyResolver> active = new ArrayList<>();
    for (final var resolver : registered) {
      if (resolver.active()) active.add(resolver);
    }
    this.resolvers = active.toArray(ProxyResolver[]::new);
  }

  @Override
  public Class<?> resolve(final Object value) {
    for (final var resolver : resolvers) {
      final var resolved = resolver.classOf(value);
      if (resolved != null) return resolved;
    }
    return null;
  }

  @Override
  public boolean active() {
    return resolvers.length > 0;
  }
}
