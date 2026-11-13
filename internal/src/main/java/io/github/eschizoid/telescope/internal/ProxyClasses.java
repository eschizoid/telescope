package io.github.eschizoid.telescope.internal;

import java.lang.System.Logger.Level;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
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
    return Registered.classOf(value, Registered.IMPLEMENTATION);
  }

  /**
   * The registered implementation, loaded on first use; null where none is registered or active.
   */
  final class Registered {

    static final ProxyClasses IMPLEMENTATION = load();

    private Registered() {}

    /** The class {@code value} stands for under {@code registered}, which may be null. */
    static Class<?> classOf(final Object value, final ProxyClasses registered) {
      if (registered == null) return value.getClass();
      final var resolved = registered.resolve(value);
      return resolved != null ? resolved : value.getClass();
    }

    /**
     * The first active implementation the loader finds. A registration this copy of the module
     * cannot load, such as one a parent loader's {@code META-INF/services} names for another copy
     * of this module, or for a core on a different loader, serves that copy and is passed over and
     * reported at {@code WARNING}; the loader moves to the next registration after each one it
     * fails on.
     */
    private static ProxyClasses load() {
      return first(ServiceLoader.load(ProxyClasses.class, ProxyClasses.class.getClassLoader()).iterator());
    }

    /**
     * The first active implementation {@code registrations} yields, passing over one it fails on.
     */
    static ProxyClasses first(final Iterator<ProxyClasses> registrations) {
      // Bounded, so a loader that failed on the same registration again could not spin forever.
      for (var attempts = 0; attempts < MAX_REGISTRATIONS; attempts++) {
        try {
          if (!registrations.hasNext()) return null;
          final var implementation = registrations.next();
          if (implementation.active()) return implementation;
        } catch (final ServiceConfigurationError unloadable) {
          // Passed over and reported; the iterator has already moved past the registration it
          // failed on.
          System.getLogger(ProxyClasses.class.getName()).log(
            Level.WARNING,
            "telescope: a proxy-class registration this module cannot load is passed over: " + unloadable.getMessage()
          );
        }
      }
      return null;
    }

    /** More registrations than any class path holds for this one internal service. */
    private static final int MAX_REGISTRATIONS = 64;
  }
}
