package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import java.lang.reflect.Method;
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
  private final MapperLookup mappers;
  private volatile Map<Method, ResolvedResponse> resolved = Map.of();

  TelescopeResponses(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    this.beanFactory = beanFactory;
    this.mappers = new MapperLookup(beanFactory, registry);
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
        element = mappers.using(using, shape.source(), "the method returns", hasTo ? to : null, "to =");
        target = MapperLookup.pairOf(using)[1];
      } else {
        target = to;
        element = mappers.forPair(shape.source(), to, annotationName);
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

  private static boolean writesBody(final HandlerMethod handler) {
    return (
      AnnotatedElementUtils.hasAnnotation(handler.getMethod(), ResponseBody.class) ||
      AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ResponseBody.class)
    );
  }
}
