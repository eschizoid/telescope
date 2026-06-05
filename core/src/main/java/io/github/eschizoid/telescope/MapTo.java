package io.github.eschizoid.telescope;

/**
 * Intermediate of {@link Telescope#map(Class)} — call {@link #to(Class)} to bind the target record.
 */
public final class MapTo<A> {

  private final Class<A> source;

  MapTo(final Class<A> source) {
    this.source = source;
  }

  /** Name the target record; returns the {@link MapBuilder} that collects field correspondences. */
  public <B> MapBuilder<A, B> to(final Class<B> target) {
    return new MapBuilder<>(source, target);
  }
}
