package io.github.eschizoid.telescope.mapping;

import java.util.Objects;

/**
 * A function a row calls on a service, made by {@link Mapping#service(Mapping.WithService)}: {@code
 * service(Catalog::price)} is {@code catalog.price(line)}, where {@code catalog} is the {@code
 * Catalog} the mapper is bound to and {@code line} the value the row hands on. The rows that take
 * one are {@code compute}, {@code toOneWay} and {@code when}.
 *
 * <p>A mapper holding such a row runs only once it is bound to a service of that class: in an
 * application on a telescope starter, the container injects the field's mapper already bound, and
 * elsewhere {@code with(service)} returns a bound copy.
 *
 * @param <S> the service
 * @param <A> what the function takes after the service
 * @param <X> what it gives
 */
public final class ServiceCall<S, A, X> {

  private final Mapping.WithService<S, A, X> function;

  ServiceCall(final Mapping.WithService<S, A, X> function) {
    this.function = Objects.requireNonNull(function, "function");
  }

  /** The function, which takes the service first. */
  public Mapping.WithService<S, A, X> function() {
    return function;
  }
}
