package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.internal.pairing.EndpointMappers;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import java.util.ArrayList;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

/**
 * Finds the mapper for a {@code (source, target)} pair the way every mapped endpoint does: a {@link
 * TelescopeProjection} bean for the pair, then a {@code Mapper} bean in the registry, then {@code
 * Telescope.mapperForward}, which takes a generated {@code @Bridge} or the same-name forward
 * mapping. {@link EndpointMappers} decides between the candidates, refuses a same-name forward
 * mapping that pairs no property, and words those refusals, the same way for every container; this
 * class finds the candidates in the bean factory. Every refusal is an {@code
 * IllegalArgumentException} whose message the caller prefixes with the handler it was resolving.
 */
final class MapperLookup {

  private final ConfigurableListableBeanFactory beanFactory;
  private final ObjectProvider<TelescopeMapperRegistry> registry;

  MapperLookup(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    this.beanFactory = beanFactory;
    this.registry = registry;
  }

  /**
   * The projection's {@code TelescopeProjection<S, T>} type arguments, either of them null when
   * unresolved.
   */
  static Class<?>[] pairOf(final Class<?> projection) {
    final var arguments = ResolvableType.forClass(projection).as(TelescopeProjection.class);
    return new Class<?>[] { arguments.resolveGeneric(0), arguments.resolveGeneric(1) };
  }

  /**
   * The bean of the {@code using} projection type, as an element mapping, once its type arguments
   * are known to map {@code source} to {@code target}. A {@code null} target is not checked. The
   * roles name each side in the message, as the endpoint sees it.
   */
  Function<Object, Object> using(
    final Class<?> projectionType,
    final Class<?> source,
    final String sourceRole,
    final Class<?> target,
    final String targetRole
  ) {
    final var pair = pairOf(projectionType);
    EndpointMappers.checkProjection(
      projectionType.getSimpleName(),
      pair[0],
      pair[1],
      source,
      sourceRole,
      target,
      targetRole
    );
    return bean(projectionType);
  }

  private Function<Object, Object> bean(final Class<?> projectionType) {
    // getIfUnique answers null both when no bean has the type and when several do with none
    // primary.
    final var projection = beanFactory.getBeanProvider(projectionType).getIfUnique();
    if (projection == null) {
      final var names = beanFactory.getBeanNamesForType(projectionType);
      if (names.length == 0) throw new IllegalArgumentException(
        "no " + projectionType.getSimpleName() + " bean. Is it annotated with @TelescopeMapper and component-scanned?"
      );
      throw new IllegalArgumentException(
        names.length +
          " " +
          projectionType.getSimpleName() +
          " beans: " +
          String.join(", ", names) +
          ". Mark one @Primary, or keep one bean of that type."
      );
    }
    return projectionMapping((TelescopeProjection<?, ?>) projection);
  }

  /** The single mapper the lookup order finds for the pair; two candidates are refused. */
  Function<Object, Object> forPair(final Class<?> source, final Class<?> target, final String annotationName) {
    final var projections = beanFactory.getBeanNamesForType(
      ResolvableType.forClassWithGenerics(TelescopeProjection.class, source, target)
    );
    final var registered = registry.getIfAvailable();
    final var mapper = registered == null ? null : registered.find(source, target).orElse(null);
    final var labels = new ArrayList<String>(projections.length);
    for (final var projection : projections) labels.add(label(projection));
    return switch (EndpointMappers.route(source, target, labels, mapper != null, annotationName)) {
      case PROJECTION -> projectionMapping(beanFactory.getBean(projections[0], TelescopeProjection.class));
      case MAPPER -> registeredMapping(mapper);
      case FORWARD -> forwardMapping(source, target);
    };
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> registeredMapping(final Mapper<?, ?> mapper) {
    final var typed = (Mapper<Object, Object>) mapper;
    return typed::forward;
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> forwardMapping(final Class<?> source, final Class<?> target) {
    EndpointMappers.checkForwardPairs(
      source,
      target,
      BridgeRegistry.find(source, target, source.getClassLoader()).isPresent()
    );
    final var forward = (ForwardMapper<Object, Object>) (ForwardMapper<?, ?>) Telescope.mapperForward(source, target);
    return forward::forward;
  }

  private String label(final String beanName) {
    final var type = beanFactory.getType(beanName);
    if (type == null) return beanName;
    final var user = ClassUtils.getUserClass(type);
    for (final var face : user.getInterfaces()) {
      if (TelescopeProjection.class.isAssignableFrom(face)) return face.getSimpleName();
    }
    return user.getSimpleName();
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> projectionMapping(final TelescopeProjection<?, ?> projection) {
    final var typed = (TelescopeProjection<Object, Object>) projection;
    return typed::map;
  }
}
