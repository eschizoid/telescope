package io.github.eschizoid.telescope.spring.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

/**
 * Reads a mapped-body parameter through Spring's own {@code @RequestBody} processor, as the wire
 * type, then maps it. Content negotiation, {@code RequestBodyAdvice}, {@code @Valid} and the
 * missing-body check all run as they do for {@code @RequestBody}, on the body as read.
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
    final var wire = new WireParameter(parameter, request.wireType(), request.wireClass(), request.required());
    return request.argument(bodies.resolveArgument(wire, mavContainer, webRequest, binderFactory));
  }
}
