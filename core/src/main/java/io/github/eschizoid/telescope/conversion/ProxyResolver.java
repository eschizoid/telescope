package io.github.eschizoid.telescope.conversion;

import java.util.ServiceLoader;

/**
 * Resolves a persistence provider's proxy to the class it stands for. A lazily fetched association
 * is a runtime subclass of its entity, and reflection over that subclass sees the proxy rather than
 * the entity: a merge source would be filed under the proxy's class, and {@code into} would bind
 * its setters to it. Core holds no provider-specific code, so it reads a proxy as its proxy class
 * unless a resolver says otherwise. {@code telescope-jpa} registers one for Hibernate's proxies.
 *
 * <p>An implementation is registered through {@link ServiceLoader}: a {@code provides} clause in a
 * module, or a {@code META-INF/services} entry on the class path. Several may be registered; they
 * are loaded once, the first time a class is resolved, and asked in the order the loader finds
 * them, the first non-null answer winning. Each needs a public no-argument constructor.
 */
public interface ProxyResolver {
  /**
   * The class {@code value} stands for, read without initializing it, or null where {@code value}
   * is not a proxy this resolver knows. A resolver that cannot tell answers null.
   *
   * @param value the value to resolve, never null
   * @return the entity class {@code value} stands for, or null
   */
  Class<?> classOf(Object value);

  /**
   * Whether this resolver can answer anything but null in this runtime, such as whether the
   * provider it knows is on the class path. A resolver that answers false is never asked, so a
   * module carrying one costs nothing where its provider is absent.
   *
   * @return whether {@link #classOf} is worth calling
   */
  default boolean active() {
    return true;
  }
}
