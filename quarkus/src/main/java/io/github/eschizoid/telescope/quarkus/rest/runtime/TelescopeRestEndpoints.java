package io.github.eschizoid.telescope.quarkus.rest.runtime;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.conversion.ServiceNeeds;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.internal.pairing.EndpointMappers;
import io.github.eschizoid.telescope.quarkus.TelescopeMapperRegistry;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The mapping of every body the build found, resolved once, at startup. Each mapper is found the
 * way the Spring starter finds one: the {@code using} projection, else the one {@link
 * TelescopeProjection} bean for the pair, else the {@code Mapper} bean the {@link
 * TelescopeMapperRegistry} holds for it, else {@code Telescope.mapperForward}. A body whose mapper
 * cannot be resolved, resolves to more than one candidate, or falls back to a same-name forward
 * mapping that pairs no property, fails startup with the method named.
 */
public class TelescopeRestEndpoints {

  private static final Logger LOGGER = System.getLogger(TelescopeRestEndpoints.class.getName());

  private final BeanManager beans;
  private volatile Map<String, ResolvedBody> responses = Map.of();
  private volatile Map<String, ResolvedBody> requests = Map.of();

  /**
   * @param beans the container the mappers are looked up in
   */
  @Inject
  public TelescopeRestEndpoints(final BeanManager beans) {
    this.beans = beans;
  }

  /** The response mapping of the resource method with this id, or {@code null}. */
  ResolvedBody response(final String methodId) {
    return responses.get(methodId);
  }

  /** The request-body mapping of the resource method with this id, or {@code null}. */
  ResolvedBody request(final String methodId) {
    return requests.get(methodId);
  }

  /**
   * Resolves the mapper of every body the build recorded.
   *
   * @param endpoints the bodies the build found
   * @throws IllegalStateException naming the first body whose mapper cannot be resolved
   */
  public void install(final List<MappedEndpoint> endpoints) {
    final var responseIndex = new HashMap<String, ResolvedBody>();
    final var requestIndex = new HashMap<String, ResolvedBody>();
    BodyValidation validation = null;
    for (final var endpoint : endpoints) {
      if (endpoint.isValidated() && validation == null) validation = bodyValidation();
      final var resolved = resolve(endpoint, endpoint.isValidated() ? validation : null);
      (endpoint.isRequest() ? requestIndex : responseIndex).put(endpoint.getMethodId(), resolved);
    }
    responses = Map.copyOf(responseIndex);
    requests = Map.copyOf(requestIndex);
  }

  private ResolvedBody resolve(final MappedEndpoint endpoint, final BodyValidation validation) {
    try {
      final var using = endpoint.getUsing();
      Class<?> source = endpoint.getSource();
      Class<?> target = endpoint.getTarget();
      final Function<Object, Object> element;
      if (using != null) {
        final var bean = usingBean(using);
        final var pair = pairOf(bean);
        if (endpoint.isRequest()) {
          if (source == null) source = pair[0];
          EndpointMappers.checkProjection(
            using.getSimpleName(),
            pair[0],
            pair[1],
            source,
            "the body is read as",
            target,
            "the parameter is"
          );
        } else {
          EndpointMappers.checkProjection(
            using.getSimpleName(),
            pair[0],
            pair[1],
            source,
            "the method returns",
            target,
            "to ="
          );
          target = pair[1];
        }
        element = projectionMapping(bean, using);
      } else {
        element = forPair(source, target, endpoint.getAnnotation(), endpoint.getWhere());
      }
      final var shape = endpoint.getShape();
      if (!endpoint.isRequest()) return new ResolvedBody(
        shape,
        source,
        element,
        shape.type(target),
        null,
        null,
        false,
        false,
        null,
        null
      );
      final Type wireType = endpoint.isHolder() ? source : shape.type(source);
      return new ResolvedBody(
        shape,
        source,
        element,
        endpoint.isHolder() ? target : shape.type(target),
        source,
        wireType,
        endpoint.isHolder(),
        endpoint.isRequired(),
        validation,
        endpoint.getValidationPath()
      );
    } catch (final RuntimeException e) {
      throw new IllegalStateException(endpoint.getWhere() + ": " + e.getMessage(), e);
    }
  }

  private Function<Object, Object> forPair(
    final Class<?> source,
    final Class<?> target,
    final String annotation,
    final String where
  ) {
    final var projections = new ArrayList<Bean<?>>();
    for (final var bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
      final var pair = pairOf(bean);
      if (pair[0] == source && pair[1] == target) projections.add(bean);
    }
    final var labels = new ArrayList<String>(projections.size());
    for (final var projection : projections) labels.add(label(projection));
    final var mapper = registered(source, target);
    return switch (EndpointMappers.route(source, target, labels, mapper != null, annotation)) {
      // A raw TelescopeProjection is not among a projection bean's types; Object always is.
      case PROJECTION -> projectionMapping(projections.getFirst(), Object.class);
      case MAPPER -> registeredMapping(mapper);
      case FORWARD -> forwardMapping(source, target, where);
    };
  }

  private Mapper<?, ?> registered(final Class<?> source, final Class<?> target) {
    final var candidates = beans.getBeans(TelescopeMapperRegistry.class);
    if (candidates.isEmpty()) return null;
    final var bean = beans.resolve(candidates);
    final var registry = (TelescopeMapperRegistry) beans.getReference(
      bean,
      TelescopeMapperRegistry.class,
      beans.createCreationalContext(bean)
    );
    return registry.find(source, target).orElse(null);
  }

  // The build registers the implementation only where Hibernate Validator is present, and records a
  // body as validated only there.
  private BodyValidation bodyValidation() {
    final var bean = beans.resolve(beans.getBeans(BodyValidation.class));
    return (BodyValidation) beans.getReference(bean, BodyValidation.class, beans.createCreationalContext(bean));
  }

  private Bean<?> usingBean(final Class<?> projectionType) {
    final var candidates = beans.getBeans(projectionType, Any.Literal.INSTANCE);
    if (candidates.isEmpty()) throw new IllegalArgumentException(
      "no " + projectionType.getSimpleName() + " bean. Is it annotated with @TelescopeMapper?"
    );
    try {
      return beans.resolve(candidates);
    } catch (final AmbiguousResolutionException ambiguous) {
      final var names = new ArrayList<String>(candidates.size());
      for (final var candidate : candidates) names.add(candidate.getBeanClass().getSimpleName());
      throw new IllegalArgumentException(
        candidates.size() +
          " " +
          projectionType.getSimpleName() +
          " beans: " +
          String.join(", ", names) +
          ". Keep one bean of that type, or mark one @Alternative with a @Priority."
      );
    }
  }

  // The bean's TelescopeProjection<S, T> type arguments, either of them null when it is not a
  // projection or the argument is not a class.
  private static Class<?>[] pairOf(final Bean<?> bean) {
    for (final var type : bean.getTypes())
      if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == TelescopeProjection.class) {
        final var arguments = parameterized.getActualTypeArguments();
        return new Class<?>[] { classOrNull(arguments[0]), classOrNull(arguments[1]) };
      }
    return new Class<?>[2];
  }

  private static Class<?> classOrNull(final Type type) {
    return type instanceof Class<?> raw ? raw : null;
  }

  private static String label(final Bean<?> bean) {
    final var type = bean.getBeanClass();
    for (final var face : type.getInterfaces())
      if (TelescopeProjection.class.isAssignableFrom(face)) return face.getSimpleName();
    return type.getSimpleName();
  }

  @SuppressWarnings("unchecked")
  private Function<Object, Object> projectionMapping(final Bean<?> bean, final Class<?> type) {
    final var projection = (TelescopeProjection<Object, Object>) beans.getReference(
      bean,
      type,
      beans.createCreationalContext(bean)
    );
    return projection::map;
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> registeredMapping(final Mapper<?, ?> mapper) {
    refuseUnbound(mapper, "Mapper<" + mapper.sourceClass().getSimpleName() + ", " + mapper.targetClass().getSimpleName() + ">");
    final var typed = (Mapper<Object, Object>) mapper;
    return typed::forward;
  }

  /** Refuses {@code mapper}, named {@code name}, where its rows call a service it is not bound to. */
  private static void refuseUnbound(final Object mapper, final String name) {
    final var needs = ServiceNeeds.of(mapper);
    if (needs == null || needs.satisfied()) return;
    final var first = needs.needs().getFirst();
    throw new IllegalArgumentException(
      EndpointMappers.endpointMapperUnbound(name, first.service().getSimpleName(), first.row(), first.in())
    );
  }

  /**
   * The bean the extension registers for the {@code @Bridge} field declaring {@code source ->
   * target} as a {@code ForwardMapper}, where its rows call a service, or null.
   */
  @SuppressWarnings("unchecked")
  private ForwardMapper<Object, Object> fieldBean(final Class<?> source, final Class<?> target) {
    for (final var provider : BridgeRegistry.serviceFields(source.getClassLoader())) {
      if (
        provider.fieldType() != ForwardMapper.class || provider.sourceType() != source || provider.targetType() != target
      ) {
        continue;
      }
      final var candidates = beans.getBeans(BridgeRegistry.beanName(provider));
      if (candidates.isEmpty()) return null;
      final var bean = beans.resolve(candidates);
      return (ForwardMapper<Object, Object>) beans.getReference(bean, Object.class, beans.createCreationalContext(bean));
    }
    return null;
  }

  // Telescope.mapperForward, as the Spring starter maps a pair with no projection or mapper bean:
  // the bridge holder beside the source, then a bridge a provider registers for the pair, then
  // the same-name runtime mapping, refused when it pairs no property. Whether ServiceLoader finds a
  // provider for the pair is logged at DEBUG, so a native image that holds none says so.
  @SuppressWarnings("unchecked")
  private Function<Object, Object> forwardMapping(final Class<?> source, final Class<?> target, final String where) {
    final var bound = fieldBean(source, target);
    if (bound != null) return bound::forward;
    final var provided = BridgeRegistry.find(source, target, source.getClassLoader()).isPresent();
    EndpointMappers.checkForwardPairs(source, target, provided);
    final var forward = (ForwardMapper<Object, Object>) (ForwardMapper<?, ?>) Telescope.mapperForward(source, target);
    refuseUnbound(forward, "ForwardMapper<" + source.getSimpleName() + ", " + target.getSimpleName() + ">");
    LOGGER.log(
      Level.DEBUG,
      () ->
        where +
        " maps " +
        source.getSimpleName() +
        " -> " +
        target.getSimpleName() +
        " through Telescope.mapperForward, " +
        (provided
          ? "with a generated bridge's provider registered for the pair"
          : "with no provider registered for the pair")
    );
    return forward::forward;
  }
}
