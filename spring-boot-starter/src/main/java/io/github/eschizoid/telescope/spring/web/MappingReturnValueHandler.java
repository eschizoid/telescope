package io.github.eschizoid.telescope.spring.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Maps a mapped handler's return value, then hands it on as if the method had returned the response
 * type. Any other handler's value, and a mapped handler's value that does not have the declared
 * shape, reaches the wrapped handler unchanged.
 */
final class MappingReturnValueHandler implements HandlerMethodReturnValueHandler {

  private final HandlerMethodReturnValueHandler delegate;
  private final TelescopeResponses responses;

  MappingReturnValueHandler(final HandlerMethodReturnValueHandler delegate, final TelescopeResponses responses) {
    this.delegate = delegate;
    this.responses = responses;
  }

  @Override
  public boolean supportsReturnType(final MethodParameter returnType) {
    return delegate.supportsReturnType(returnType);
  }

  @Override
  public void handleReturnValue(
    final Object returnValue,
    final MethodParameter returnType,
    final ModelAndViewContainer mavContainer,
    final NativeWebRequest webRequest
  ) throws Exception {
    final var response = returnType.getMethod() == null ? null : responses.find(returnType.getMethod());
    if (response == null || !response.shape().accepts(returnValue)) {
      delegate.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
      return;
    }
    delegate.handleReturnValue(
      response.apply(returnValue),
      new MappedReturnType(returnType, response.mappedType()),
      mavContainer,
      webRequest
    );
  }
}
