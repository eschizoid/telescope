package io.github.eschizoid.telescope.internal;

import java.util.ServiceLoader;

/**
 * Finds the class a persistence provider's proxy stands for, which {@link Beans#persistentClassOf}
 * keys its caches on. This module holds no provider-specific code: {@code telescope-core} registers
 * the one implementation through {@link ServiceLoader}, and that implementation consults the
 * resolvers registered through core's public SPI. Without one, every value is its own class.
 */
public interface ProxyClasses {
  /**
   * The class {@code value} stands for, without initializing it, or null where no resolver knows
   * {@code value} as a proxy.
   *
   * @param value a non-null value
   * @return the class {@code value} stands for, or null
   */
  Class<?> resolve(Object value);

  /**
   * Whether any resolver is registered. An implementation that answers false is not consulted, so a
   * read with no resolver registered costs no call.
   *
   * @return whether {@link #resolve} can answer anything but null
   */
  boolean active();

  /**
   * The class {@code value} stands for: what the registered implementation resolves it to, and
   * otherwise {@code value.getClass()}.
   *
   * @param value a non-null value
   * @return the class {@code value} stands for
   */
  static Class<?> classOf(final Object value) {
    final var registered = Registered.IMPLEMENTATION;
    if (registered == null) return value.getClass();
    final var resolved = registered.resolve(value);
    return resolved != null ? resolved : value.getClass();
  }

  /**
   * The registered implementation, loaded on first use; null where none is registered or active.
   */
  final class Registered {

    static final ProxyClasses IMPLEMENTATION = load();

    private Registered() {}

    private static ProxyClasses load() {
      for (final var implementation : ServiceLoader.load(ProxyClasses.class, ProxyClasses.class.getClassLoader())) {
        if (implementation.active()) return implementation;
      }
      return null;
    }
  }
}
