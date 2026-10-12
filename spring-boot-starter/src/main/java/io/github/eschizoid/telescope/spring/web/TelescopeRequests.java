package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import org.springframework.validation.annotation.ValidationAnnotationUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The request-body mapping of every handler parameter that carries {@link TelescopeRequestBody} or
 * has the type {@link TelescopeRequest}, resolved once every singleton exists. A parameter whose
 * mapping cannot be resolved, or resolves to more than one mapper, fails startup with the parameter
 * named.
 */
final class TelescopeRequests implements SmartInitializingSingleton {

  private static final String ANNOTATION = "@" + TelescopeRequestBody.class.getSimpleName();

  private final ConfigurableListableBeanFactory beanFactory;
  private final MapperLookup mappers;
  private volatile Map<Key, ResolvedRequest> resolved = Map.of();

  TelescopeRequests(
    final ConfigurableListableBeanFactory beanFactory,
    final ObjectProvider<TelescopeMapperRegistry> registry
  ) {
    this.beanFactory = beanFactory;
    this.mappers = new MapperLookup(beanFactory, registry);
  }

  /** The mapping for the handler parameter, or {@code null} when it is not a mapped body. */
  ResolvedRequest find(final Method method, final int index) {
    return resolved.get(new Key(method, index));
  }

  /** Whether the parameter asks for a mapped body, by its annotation or by its type. */
  static boolean isMappedBody(final MethodParameter parameter) {
    return (
      parameter.hasParameterAnnotation(TelescopeRequestBody.class) ||
      parameter.getParameterType() == TelescopeRequest.class
    );
  }

  @Override
  public void afterSingletonsInstantiated() {
    final var index = new HashMap<Key, ResolvedRequest>();
    for (final var mapping : beanFactory.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (final var handler : mapping.getHandlerMethods().values()) {
        for (final var parameter : handler.getMethodParameters()) {
          if (!isMappedBody(parameter)) continue;
          final var key = new Key(handler.getMethod(), parameter.getParameterIndex());
          // A method held by more than one handler mapping is resolved once.
          if (!index.containsKey(key)) index.put(key, resolve(handler, parameter));
        }
      }
    }
    resolved = Map.copyOf(index);
  }

  private ResolvedRequest resolve(final HandlerMethod handler, final MethodParameter parameter) {
    final var named = parameter.clone();
    named.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
    final var name =
      named.getParameterName() == null ? "arg" + parameter.getParameterIndex() : named.getParameterName();
    final var where =
      ClassUtils.getUserClass(handler.getBeanType()).getSimpleName() +
      "#" +
      handler.getMethod().getName() +
      "(" +
      name +
      ")";
    try {
      if (parameter.hasParameterAnnotation(RequestBody.class)) throw new IllegalArgumentException(
        "remove @RequestBody; " + ANNOTATION + " reads the body."
      );
      final var annotation = parameter.getParameterAnnotation(TelescopeRequestBody.class);
      final Class<?> from = annotation == null ? Void.class : annotation.from();
      final Class<?> using = annotation == null ? TelescopeProjection.class : annotation.using();
      final var required = annotation == null || annotation.required();
      final var hasFrom = from != Void.class;
      final var hasUsing = using != TelescopeProjection.class;
      final var holder = parameter.getParameterType() == TelescopeRequest.class;

      final ResponseShape shape;
      final Class<?> wire;
      if (holder) {
        if (hasFrom) throw new IllegalArgumentException(
          "from is not allowed on a TelescopeRequest parameter; its first type argument is the wire type."
        );
        final var arguments = ResolvableType.forMethodParameter(parameter).getGenerics();
        final var original = arguments.length == 2 ? concrete(arguments[0]) : null;
        final var mapped = arguments.length == 2 ? concrete(arguments[1]) : null;
        if (original == null || mapped == null) throw new IllegalArgumentException(
          "TelescopeRequest needs two concrete type arguments, such as TelescopeRequest<CreateUserRequest, UserDto>."
        );
        shape = parameterShape(mapped);
        wire = original;
      } else {
        shape = parameterShape(parameter.getGenericParameterType());
        if (!hasFrom && !hasUsing) throw new IllegalArgumentException("set from or using on " + ANNOTATION + ".");
        wire = hasFrom ? from : MapperLookup.pairOf(using)[0];
      }

      final var target = shape.source();
      final Function<Object, Object> element;
      if (hasUsing) {
        final var pair = MapperLookup.pairOf(using);
        if (pair[0] != wire) throw new IllegalArgumentException(
          using.getSimpleName() +
            " maps " +
            MapperLookup.name(pair[0]) +
            ", but the body is read as " +
            MapperLookup.name(wire)
        );
        if (pair[1] != target) throw new IllegalArgumentException(
          using.getSimpleName() +
            " maps to " +
            MapperLookup.name(pair[1]) +
            ", but the parameter is " +
            target.getSimpleName()
        );
        element = mappers.using(using);
      } else {
        element = mappers.forPair(wire, target, ANNOTATION);
      }
      final Type wireType = holder ? wire : shape.mappedType(wire);
      final var view = new WireParameter(parameter, wireType, ResolvableType.forType(wireType).toClass(), required);
      view.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
      return new ResolvedRequest(shape, element, wireType, view, holder, required, validationHints(parameter));
    } catch (final RuntimeException e) {
      throw new IllegalStateException(where + ": " + e.getMessage(), e);
    }
  }

  // The body is read into the parameter's own container: a single value, a list or a set.
  private static ResponseShape parameterShape(final Type type) {
    final ResponseShape shape;
    try {
      shape = ResponseShape.of(type);
    } catch (final IllegalArgumentException e) {
      throw unsupported(type);
    }
    if (
      shape instanceof ResponseShape.Single ||
      shape instanceof ResponseShape.ListOf ||
      shape instanceof ResponseShape.SetOf
    ) {
      return shape;
    }
    throw unsupported(type);
  }

  private static IllegalArgumentException unsupported(final Type type) {
    return new IllegalArgumentException(
      "unsupported parameter type " +
        type.getTypeName() +
        ". A mapped request body is read into T, List<T>, Collection<T> or Set<T>, or a TelescopeRequest<S, T>."
    );
  }

  private static Object[] validationHints(final MethodParameter parameter) {
    for (final var annotation : parameter.getParameterAnnotations()) {
      final var hints = ValidationAnnotationUtils.determineValidationHints(annotation);
      if (hints != null) return hints;
    }
    return null;
  }

  private static Class<?> concrete(final ResolvableType type) {
    return type.getType() instanceof Class<?> raw && raw.getTypeParameters().length == 0 ? raw : null;
  }

  private record Key(Method method, int index) {}
}
