package io.github.eschizoid.telescope.internal;

/**
 * The proxy resolution this module's tests register through {@code META-INF/services}: an instance
 * of {@link Proxy} stands for {@link BeansTest.PersistentClass.PlainBean}, and every other value is
 * unknown to it.
 */
public final class TestProxyClasses implements ProxyClasses {

  /** A subclass standing in for a provider's proxy of {@code PlainBean}. */
  public static final class Proxy extends BeansTest.PersistentClass.PlainBean {}

  public TestProxyClasses() {}

  @Override
  public Class<?> resolve(final Object value) {
    return value instanceof Proxy ? BeansTest.PersistentClass.PlainBean.class : null;
  }

  @Override
  public boolean active() {
    return true;
  }
}
