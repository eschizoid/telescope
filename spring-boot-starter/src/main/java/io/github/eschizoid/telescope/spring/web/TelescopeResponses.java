package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The mapping of every handler method that carries {@link TelescopeResponse}, resolved once every
 * singleton exists. A method whose mapping cannot be resolved, or resolves to more than one mapper,
 * fails startup with the method named.
 */
final class TelescopeResponses implements SmartInitializingSingleton {

  private final ConfigurableListableBeanFactory beanFactory;
  private final ObjectProvider<TelescopeMapperRegistry> registry;
  private volatile Map<Method, ResolvedResponse> resolved = Map.of();

  TelescopeResponses(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    this.beanFactory = beanFactory;
    this.registry = registry;
  }

  /** The mapping for {@code method}, or {@code null} when the method is not a mapped handler. */
  ResolvedResponse find(final Method method) {
    return resolved.get(method);
  }

  @Override
  public void afterSingletonsInstantiated() {
    final var index = new HashMap<Method, ResolvedResponse>();
    for (final var mapping : beanFactory.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (final var handler : mapping.getHandlerMethods().values()) {
        final var annotation = MergedAnnotations.from(handler.getMethod(), SearchStrategy.TYPE_HIERARCHY).get(
          TelescopeResponse.class
        );
        // A method held by more than one handler mapping is resolved once.
        if (annotation.isPresent() && !index.containsKey(handler.getMethod())) {
          index.put(handler.getMethod(), resolve(handler, annotation));
        }
      }
    }
    resolved = Map.copyOf(index);
  }

  private ResolvedResponse resolve(final HandlerMethod handler, final MergedAnnotation<TelescopeResponse> annotation) {
    final var method = handler.getMethod();
    final var where = ClassUtils.getUserClass(handler.getBeanType()).getSimpleName() + "#" + method.getName();
    final var annotationName = "@" + annotation.getRoot().getType().getSimpleName();
    try {
      final var shape = ResponseShape.of(method.getGenericReturnType());
      if (!shape.isEntity() && !writesBody(handler)) throw new IllegalArgumentException(
        "the method returns a view name, not a response body. Annotate the controller with @RestController or the" +
          " method with @ResponseBody."
      );
      final var to = annotation.getClass("to");
      final var using = annotation.getClass("using");
      final var hasTo = to != Void.class;
      final var hasUsing = using != TelescopeProjection.class;
      if (!hasTo && !hasUsing) throw new IllegalArgumentException("set to or using on " + annotationName + ".");
      final Class<?> target;
      final Function<Object, Object> element;
      if (hasUsing) {
        final var arguments = ResolvableType.forClass(using).as(TelescopeProjection.class);
        final var projectionSource = arguments.resolveGeneric(0);
        final var projectionTarget = arguments.resolveGeneric(1);
        if (projectionSource != shape.source()) throw new IllegalArgumentException(
          using.getSimpleName() + " maps " + name(projectionSource) + ", but the method returns " + name(shape.source())
        );
        if (hasTo && projectionTarget != to) throw new IllegalArgumentException(
          using.getSimpleName() + " maps to " + name(projectionTarget) + ", but to = " + to.getSimpleName()
        );
        // getIfUnique answers null both when no bean has the type and when several do with none
        // primary.
        final var projection = beanFactory.getBeanProvider(using).getIfUnique();
        if (projection == null) {
          final var names = beanFactory.getBeanNamesForType(using);
          if (names.length == 0) throw new IllegalArgumentException(
            "no " + using.getSimpleName() + " bean. Is it annotated with @TelescopeMapper and component-scanned?"
          );
          throw new IllegalArgumentException(
            names.length +
              " " +
              using.getSimpleName() +
              " beans: " +
              String.join(", ", names) +
              ". Mark one @Primary, or keep one bean of that type."
          );
        }
        target = projectionTarget;
        element = projectionMapping((TelescopeProjection<?, ?>) projection);
      } else {
        target = to;
        element = lookup(shape.source(), to, annotationName);
      }
      final var mappedType = shape.mappedType(target);
      return new ResolvedResponse(
        shape,
        element,
        mappedType,
        ResolvableType.forType(mappedType).toClass(),
        shape.bodyType(target)
      );
    } catch (final RuntimeException e) {
      throw new IllegalStateException(where + ": " + e.getMessage(), e);
    }
  }

  private Function<Object, Object> lookup(final Class<?> source, final Class<?> target, final String annotationName) {
    final var projections = beanFactory.getBeanNamesForType(
      ResolvableType.forClassWithGenerics(TelescopeProjection.class, source, target)
    );
    final var registered = registry.getIfAvailable();
    final var mapper = registered == null ? null : registered.find(source, target).orElse(null);
    final var candidates = new ArrayList<String>();
    for (final var projection : projections) candidates.add(label(projection));
    if (mapper != null) candidates.add("a Mapper<" + source.getSimpleName() + ", " + target.getSimpleName() + "> bean");
    if (candidates.size() > 1) throw new IllegalArgumentException(
      candidates.size() +
        " mappers for " +
        source.getSimpleName() +
        " -> " +
        target.getSimpleName() +
        ": " +
        String.join(", ", candidates) +
        ". Add using = ... to " +
        annotationName +
        " to choose one."
    );
    if (projections.length == 1) return projectionMapping(
      beanFactory.getBean(projections[0], TelescopeProjection.class)
    );
    if (mapper != null) {
      @SuppressWarnings("unchecked")
      final var typed = (Mapper<Object, Object>) mapper;
      return typed::forward;
    }
    @SuppressWarnings("unchecked")
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

  private static boolean writesBody(final HandlerMethod handler) {
    return (
      AnnotatedElementUtils.hasAnnotation(handler.getMethod(), ResponseBody.class) ||
      AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ResponseBody.class)
    );
  }

  @SuppressWarnings("unchecked")
  private static Function<Object, Object> projectionMapping(final TelescopeProjection<?, ?> projection) {
    final var typed = (TelescopeProjection<Object, Object>) projection;
    return typed::map;
  }

  private static String name(final Class<?> type) {
    return type == null ? "an unresolved type" : type.getSimpleName();
  }
}
