package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.conversion.ProxyResolver;
import io.github.eschizoid.telescope.internal.MhAccessors;
import io.github.eschizoid.telescope.internal.NativeImage;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Resolves a Hibernate proxy to the entity class it stands for, through {@code
 * HibernateProxy.getHibernateLazyInitializer().getPersistentClass()}, neither of which initializes
 * the proxy, so a proxy is resolved before its row is read. Hibernate is not a compile-time
 * dependency: its two types are looked up by name once, and the resolver is inactive where they
 * are absent.
 *
 * <p>The two calls are bound once, as {@code LambdaMetafactory} functions on a JVM and as {@code
 * MethodHandle} closures inside a native image, which defines no class at run time; the proxy
 * types are then reached through the image's own reflection metadata for them.
 *
 * <p>Public only because {@link ServiceLoader} instantiates it; not called directly.
 */
public final class HibernateProxies implements ProxyResolver {

  private static final String PROXY = "org.hibernate.proxy.HibernateProxy";
  private static final String LAZY_INITIALIZER = "org.hibernate.proxy.LazyInitializer";

  /** {@code HibernateProxy}, or null where Hibernate is not on the class path. */
  private static final Class<?> PROXY_TYPE = load(PROXY);

  /** {@code HibernateProxy#getHibernateLazyInitializer()}, or null. */
  private static final Function<Object, Object> INITIALIZER_OF = bind(PROXY_TYPE, "getHibernateLazyInitializer");

  /** {@code LazyInitializer#getPersistentClass()}, or null. */
  private static final Function<Object, Object> PERSISTENT_CLASS_OF = bind(load(LAZY_INITIALIZER), "getPersistentClass");

  /** Called by {@link ServiceLoader}. */
  public HibernateProxies() {}

  @Override
  public Class<?> classOf(final Object value) {
    if (!PROXY_TYPE.isInstance(value)) return null;
    try {
      final var initializer = INITIALIZER_OF.apply(value);
      return initializer == null ? null : (Class<?>) PERSISTENT_CLASS_OF.apply(initializer);
    } catch (final RuntimeException unresolved) {
      // A proxy whose initializer cannot answer is read as its own class, as it is with no
      // resolver at all.
      return null;
    }
  }

  @Override
  public boolean active() {
    return PROXY_TYPE != null && INITIALIZER_OF != null && PERSISTENT_CLASS_OF != null;
  }

  private static Class<?> load(final String name) {
    try {
      return Class.forName(name, false, HibernateProxies.class.getClassLoader());
    } catch (final ClassNotFoundException | LinkageError absent) {
      return null;
    }
  }

  /** The no-argument method {@code name} of {@code owner} as a function, or null where it is absent. */
  @SuppressWarnings("unchecked")
  private static Function<Object, Object> bind(final Class<?> owner, final String name) {
    if (owner == null) return null;
    try {
      final Method method = owner.getMethod(name);
      // On the module path this module reads Hibernate's only once it says so; on the class path
      // both are the unnamed module and this does nothing.
      HibernateProxies.class.getModule().addReads(owner.getModule());
      final var lookup = MethodHandles.lookup();
      final MethodHandle handle = lookup.unreflect(method);
      if (NativeImage.IN_IMAGE) return MhAccessors.function(handle);
      final var site = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(method.getReturnType(), owner)
      );
      return (Function<Object, Object>) site.getTarget().invokeExact();
    } catch (final Throwable unbound) {
      return null;
    }
  }
}
