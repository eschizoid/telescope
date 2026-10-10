package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed registry of every {@link Mapper} bean Spring's {@code ApplicationContext} can resolve.
 * Indexed by {@code (sourceClass, targetClass)} pair so callers can look up a mapper by its type
 * pair without having to inject a specific named bean. Built automatically by {@link
 * TelescopeAutoConfiguration} when the starter is on the classpath.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Spring already resolves parametric beans by type — {@code @Autowired Mapper<Order,
 * OrderEntity>} just works. The registry's value-add is <em>polymorphic dispatch</em>: a generic
 * service that receives an {@code Object} of unknown type can call {@code registry.get(src.class,
 * Target.class).forward(src)} to convert it, without writing a switch over every known type pair.
 *
 * <pre>{@code
 * @Service
 * public class GenericConverter {
 *   private final TelescopeMapperRegistry registry;
 *   public GenericConverter(TelescopeMapperRegistry registry) { this.registry = registry; }
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
 * <p>The autoconfig wires Spring's {@code Collection<Mapper<?, ?>>} into the registry's constructor
 * — every {@code Mapper} bean in the context is indexed. Mappers built via {@link
 * io.github.eschizoid.telescope.Telescope#mapper Telescope.mapper(...)} expose {@link
 * Mapper#sourceClass()} / {@link Mapper#targetClass()} that the registry reads to build the index.
 *
 * <p>The registry holds one mapper per {@code (sourceClass, targetClass)} pair, and two {@code
 * Mapper} beans for the same pair make the constructor throw {@link IllegalStateException}, so the
 * context fails to start. {@code telescope.registry.fail-fast} does not change this; it governs
 * only lookups of a missing pair. A {@code @Qualifier} does not keep a bean out of the registry,
 * because the autoconfiguration collects every {@code Mapper} bean that is a default autowire
 * candidate, qualified or not. A second mapper for a registered pair stays out of the registry when
 * it is declared with {@code @Bean(defaultCandidate = false)} and injected by its qualifier, when
 * it is wrapped in a type of the application's own, or when it is built where it is used.
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
          ". The registry holds one Mapper per type pair and collects every Mapper bean, qualified or not, so a" +
          " @Qualifier does not keep the second one out. Keep one Mapper bean for the pair, and declare the other with" +
          " @Bean(defaultCandidate = false) and inject it by its qualifier, wrap it in a type of your own, or build it" +
          " where it is used."
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
        "> registered. Define a @Bean Mapper<" +
        sourceClass.getSimpleName() +
        ", " +
        targetClass.getSimpleName() +
        "> in your @Configuration."
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
