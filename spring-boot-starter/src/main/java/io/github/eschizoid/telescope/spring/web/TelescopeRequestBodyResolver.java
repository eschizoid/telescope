package io.github.eschizoid.telescope.spring.web;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

/**
 * Reads a mapped-body parameter through Spring's own {@code @RequestBody} processor, as the wire
 * type, validates it, then maps it. Content negotiation, {@code RequestBodyAdvice} and the
 * missing-body check run as they do for {@code @RequestBody}. Validation runs on the body as read,
 * each element of a list or set body on its own, with a binder built for the value rather than for
 * the parameter, so method validation on the handler cannot take the validator away; a violation is
 * the usual {@code MethodArgumentNotValidException} before anything is mapped.
 */
final class TelescopeRequestBodyResolver implements HandlerMethodArgumentResolver {

  private final RequestResponseBodyMethodProcessor bodies;
  private final TelescopeRequests requests;

  TelescopeRequestBodyResolver(final RequestResponseBodyMethodProcessor bodies, final TelescopeRequests requests) {
    this.bodies = bodies;
    this.requests = requests;
  }

  @Override
  public boolean supportsParameter(final MethodParameter parameter) {
    return TelescopeRequests.isMappedBody(parameter);
  }

  @Override
  public Object resolveArgument(
    final MethodParameter parameter,
    final ModelAndViewContainer mavContainer,
    final NativeWebRequest webRequest,
    final WebDataBinderFactory binderFactory
  ) throws Exception {
    final var request = requests.find(parameter.getMethod(), parameter.getParameterIndex());
    if (request == null) throw new IllegalStateException(
      "no request-body mapping was resolved at startup for " + parameter.getExecutable().toGenericString()
    );
    final var original = bodies.resolveArgument(request.wire(), mavContainer, webRequest, binderFactory);
    if (original != null && request.validationHints() != null && binderFactory != null) {
      for (final var value : request.values(original)) {
        if (value == null) continue;
        final var binder = binderFactory.createBinder(webRequest, value, Conventions.getVariableName(value));
        binder.validate(request.validationHints());
        if (binder.getBindingResult().hasErrors()) {
          throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
        }
      }
    }
    return request.argument(original);
  }
}
