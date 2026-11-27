package io.github.eschizoid.telescope.mapping;

import java.util.Objects;

/**
 * A value a row asks a service for, made by {@link Mapping#service(Mapping.FromService)}: {@code
 * service(Clock::instant)} is {@code clock.instant()}, where {@code clock} is the {@code Clock} the
 * mapper is bound to. The rows that take one are {@code compute} and {@code toOrElseGet}.
 *
 * <p>As for a {@link ServiceCall}, a mapper holding such a row runs only once it is bound to a
 * service of that class.
 *
 * @param <S> the service
 * @param <X> what it gives
 */
public final class ServiceSupplier<S, X> {

  private final Mapping.FromService<S, X> function;

  ServiceSupplier(final Mapping.FromService<S, X> function) {
    this.function = Objects.requireNonNull(function, "function");
  }

  /** The function, which takes the service alone. */
  public Mapping.FromService<S, X> function() {
    return function;
  }
}
