package io.github.eschizoid.telescope.quarkus;

import io.github.eschizoid.telescope.Mapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed registry of every {@link Mapper} bean Quarkus' CDI container can resolve. Indexed by {@code
 * (sourceClass, targetClass)} pair so callers can look up a mapper by its type pair without having
 * to inject a specific {@code @Named} bean. Built automatically by {@link TelescopeProducer} when
 * the extension is on the classpath.
 *
 * <h2>Why this exists</h2>
 *
 * <p>CDI already resolves parametric beans by type — {@code @Inject Mapper<Order, OrderEntity>}
 * just works. The registry's value-add is <em>polymorphic dispatch</em>: a generic service that
 * receives an {@code Object} of unknown type can call {@code registry.get(src.class,
 * Target.class).forward(src)} to convert it, without writing a switch over every known type pair.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class GenericConverter {
 *   @Inject TelescopeMapperRegistry registry;
 *
 *   public <A, B> B convert(A src, Class<B> targetClass) {
 *     @SuppressWarnings("unchecked")
 *     Mapper<A, B> mapper = (Mapper<A, B>) registry.get(src.getClass(), targetClass);
 *     return mapper.forward(src);
 *   }
 * }
 * }</pre>
 *
 * <h2>Construction</h2>
 *
 * <p>{@link TelescopeProducer} injects every {@code Mapper<?, ?>} bean via the {@code @All
 * List<Mapper<?, ?>>} CDI pattern and hands them to the registry constructor. Mappers built via
 * {@link io.github.eschizoid.telescope.Telescope#mapper Telescope.mapper(...)} expose {@link
 * Mapper#sourceClass()} / {@link Mapper#targetClass()} that the registry reads to build the index.
 *
 * <p>Duplicate {@code (srcClass, tgtClass)} pairs cause an {@link IllegalStateException} at
 * construction time — the {@code (sourceClass, targetClass)} pair must uniquely identify a mapper.
 * If you genuinely have two semantically-different mappers for the same pair, qualify them with CDI
 * {@code @Named} or {@code @Qualifier} annotations and {@code @Inject} the specific bean instead of
 * going through the registry.
 */
public class TelescopeMapperRegistry {

  private final Map<TypePair, Mapper<?, ?>> mappers;
  private final boolean failFast;

  public TelescopeMapperRegistry(final Collection<Mapper<?, ?>> mappers, final boolean failFast) {
    this.failFast = failFast;
    final var index = new HashMap<TypePair, Mapper<?, ?>>(mappers.size() * 2);
    for (final var mapper : mappers) {
      final var key = new TypePair(mapper.sourceClass(), mapper.targetClass());
      final var prior = index.put(key, mapper);
      if (prior != null) throw new IllegalStateException(
        "Duplicate Mapper for type pair " +
          mapper.sourceClass().getName() +
          " -> " +
          mapper.targetClass().getName() +
          ". Qualify the beans with @Named / @Qualifier and inject the specific instance instead of relying on the registry."
      );
    }
    this.mappers = Map.copyOf(index);
  }

  /**
   * Look up the registered {@link Mapper} for {@code (sourceClass, targetClass)}. Behaviour on
   * absence depends on {@code telescope.registry.fail-fast} (default {@code true}): when {@code
   * true}, throws {@link IllegalArgumentException}; when {@code false}, returns {@code null}.
   */
  @SuppressWarnings("unchecked")
  public <A, B> Mapper<A, B> get(final Class<A> sourceClass, final Class<B> targetClass) {
    Objects.requireNonNull(sourceClass, "sourceClass");
    Objects.requireNonNull(targetClass, "targetClass");
    final var mapper = mappers.get(new TypePair(sourceClass, targetClass));
    if (mapper == null && failFast) throw new IllegalArgumentException(
      "No Mapper<" +
        sourceClass.getName() +
        ", " +
        targetClass.getName() +
        "> registered. Define a CDI producer or @ApplicationScoped class returning Mapper<" +
        sourceClass.getSimpleName() +
        ", " +
        targetClass.getSimpleName() +
        ">."
    );
    return (Mapper<A, B>) mapper;
  }

  /**
   * Look up the registered {@link Mapper} as an {@link Optional}. Useful when {@code fail-fast} is
   * on but the caller wants to handle absence without an exception (e.g., a generic dispatch where
   * "no mapper" is a recoverable case).
   */
  @SuppressWarnings("unchecked")
  public <A, B> Optional<Mapper<A, B>> find(final Class<A> sourceClass, final Class<B> targetClass) {
    Objects.requireNonNull(sourceClass, "sourceClass");
    Objects.requireNonNull(targetClass, "targetClass");
    return Optional.ofNullable((Mapper<A, B>) mappers.get(new TypePair(sourceClass, targetClass)));
  }

  /** The number of mappers indexed by the registry. */
  public int size() {
    return mappers.size();
  }

  /**
   * Whether a {@link Mapper} is registered for the given type pair. Avoids the {@code
   * fail-fast}-driven throw of {@link #get}.
   */
  public boolean contains(final Class<?> sourceClass, final Class<?> targetClass) {
    return mappers.containsKey(new TypePair(sourceClass, targetClass));
  }

  private record TypePair(Class<?> source, Class<?> target) {}
}
