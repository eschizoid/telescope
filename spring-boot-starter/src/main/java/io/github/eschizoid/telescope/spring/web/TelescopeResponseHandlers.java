package io.github.eschizoid.telescope.spring.web;

import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityReturnValueHandler;

/**
 * Wraps the handlers that write a response body once the adapter has built its handler list,
 * keeping every handler in its position: the {@code @ResponseBody} processor, and both the {@code
 * ResponseEntity} handler and the {@code HttpEntity} processor it delegates to, since either can be
 * the one the list selects.
 */
final class TelescopeResponseHandlers implements BeanPostProcessor {

  private final ObjectProvider<TelescopeResponses> responses;

  TelescopeResponseHandlers(final ObjectProvider<TelescopeResponses> responses) {
    this.responses = responses;
  }

  @Override
  public Object postProcessAfterInitialization(final Object bean, final String beanName) {
    if (!(bean instanceof RequestMappingHandlerAdapter adapter)) return bean;
    final var handlers = adapter.getReturnValueHandlers();
    if (handlers == null) return bean;
    final var mapped = responses.getObject();
    final var wrapped = new ArrayList<HandlerMethodReturnValueHandler>(handlers.size());
    for (final var handler : handlers) {
      final var writesBody =
        handler instanceof RequestResponseBodyMethodProcessor ||
        handler instanceof ResponseEntityReturnValueHandler ||
        handler instanceof HttpEntityMethodProcessor;
      wrapped.add(writesBody ? new MappingReturnValueHandler(handler, mapped) : handler);
    }
    adapter.setReturnValueHandlers(wrapped);
    return bean;
  }
}
