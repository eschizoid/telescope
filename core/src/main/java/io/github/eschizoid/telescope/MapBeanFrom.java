package io.github.eschizoid.telescope;

/**
 * Intermediate of {@link Telescope#mapBean(Class)} — call {@link #to(Class)} to bind the target
 * type and continue into {@link MapBeanTo}.
 */
public final class MapBeanFrom<A> {

  private final Class<A> source;

  MapBeanFrom(final Class<A> source) {
    this.source = source;
  }

  public <B> MapBeanTo<A, B> to(final Class<B> target) {
    return new MapBeanTo<>(source, target);
  }
}
